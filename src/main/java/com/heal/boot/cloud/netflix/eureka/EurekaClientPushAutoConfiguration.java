package com.heal.boot.cloud.netflix.eureka;

import cn.com.duiba.boot.event.ContextClosingEvent;
import cn.com.duiba.boot.event.MainContextRefreshedEvent;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.google.common.base.Joiner;
import com.netflix.discovery.EurekaClient;
import com.netflix.loadbalancer.DynamicServerListLoadBalancer;
import com.netflix.loadbalancer.ILoadBalancer;
import org.apache.commons.lang3.RandomUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.message.BasicNameValuePair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cloud.netflix.ribbon.SpringClientFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Configuration;

import javax.annotation.PreDestroy;
import javax.annotation.Resource;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.*;

/**
 *
 * 类的作用：目前某个服务[注册/取消注册]到eurekaServer时，依赖方需要等30秒至1分钟才能知晓，这个类用于与eurekaServer保持长连接，并及时得到依赖变更，从而尽快更新服务列表。
 *
 * <br/>
 *
 * 让eureka客户端使用sse技术连接到eurekaServer上，当eurekaServer检测到服务器注册、取消注册等事件时，通过sse主动通知客户端，随后客户端刷新服务器列表。
 * 本地刷新的动作分为两步：
 *      1.让DiscoveryClient主动获取一次服务器列表，
 *      2.对于每个应用（比如stock-service），调用对应的DynamicServerListLoadBalancer的updateListOfServers方法，触发更新服务器列表
 *      （这里有个地方要注意，由于eurekaServer之间复制信息是异步的，本地可能拿不到刚更新的服务器列表，后续需要观察,如果不行，需要改进,最好是到与sse同个服务器去取）
 */
@Configuration
@ConditionalOnClass(EurekaClient.class)
@ConditionalOnProperty(value = "eureka.client.enabled", matchIfMissing = true)
public class EurekaClientPushAutoConfiguration {

    /**
     * sse事件内容的前缀
     */
    private static final String SSE_PREFIX = "data:";
    /**
     * eureka命令，表示需要客户端刷新某些应用的列表。
     */
    public static final String EUREKA_COMMAND_REFRESH = "refresh";

    private static final Logger logger = LoggerFactory.getLogger(EurekaClientPushAutoConfiguration.class);

    @Resource(name="eurekaClient")
    private EurekaClient eurekaClient;
    @Resource
    private ApplicationContext applicationContext;

    @Value("${spring.application.name}")
    private String currentAppName;

    @Resource
    private SpringClientFactory ribbonSpringClientFactory;

    @Resource
    private CloseableHttpClient httpClient;

    private volatile HttpPost curHttpPost;

    private volatile String relyAppNames;

    private int continuousIoExceptionTimes;

    private Thread eurekaClientPushThread = new Thread("eurekaClientPushThread"){
        @Override
        public void run(){
            while(true) {
                try {
                    refreshRelyAppNames();
                    if(StringUtils.isBlank(relyAppNames)){//如果没有依赖的应用，则休眠60秒后继续
                        Thread.sleep(60000);
                        continue;
                    }
                    boolean isContinue = connectEurekaServerSse();
                    if(!isContinue){
                        break;
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (Exception e){
                    logger.error("处理eureka sse失败", e);
                    try {
                        Thread.sleep(10000);
                    } catch (InterruptedException e1) {
                        Thread.currentThread().interrupt();
                    }
                }

                if(Thread.currentThread().isInterrupted()){
                    break;
                }
            }
        }
    };

    @EventListener(MainContextRefreshedEvent.class)
    public void onMainContextRefreshed() {
        eurekaClientPushThread.start();
    }

    /**
     * 刷新要依赖的eureka应用，当这些应用在eureka中上下线时，本应用会立即收到通知。
     * <br/>
     */
    private void refreshRelyAppNames(){
        //拿到需要用到的所有应用名
        Set<String> relyAppNameSet = ribbonSpringClientFactory.getContextNames();
        List<String> tempList = new ArrayList<>(relyAppNameSet);
        if(!tempList.contains(currentAppName)) {
            tempList.add(currentAppName);//hazelcast、RocketMQ多场景测试等功能需要关心当前应用的所有实例，所以也加上对当前应用的监听。
        }
        tempList.remove("not-exists");//移除zuul定义的特殊应用
        Collections.sort(tempList);
        relyAppNames = Joiner.on(",").join(tempList);
    }

    @PreDestroy
    @EventListener(ContextClosingEvent.class)
    public synchronized void destroy(){
        if(eurekaClientPushThread != null) {
            eurekaClientPushThread.interrupt();
            eurekaClientPushThread = null;
        }
        HttpPost h = curHttpPost;
        if(h != null) {
            h.abort();
            curHttpPost = null;
        }
    }

    /**
     * 与eurekaServer建立长连接并监听感兴趣的服务上下线事件。
     *
     * @return
     * @throws Exception
     */
    private boolean connectEurekaServerSse() throws Exception {
        String oneEurekaServerUrl = getRandomEurekaServerUrl();
        if(oneEurekaServerUrl == null){
            logger.info("eureka server urls is not exists, will not push eureka's registry");
            return false;
        }

        oneEurekaServerUrl = oneEurekaServerUrl.substring(0, oneEurekaServerUrl.lastIndexOf("/eureka")) + "/sse/eureka";

        HttpPost req = new HttpPost(oneEurekaServerUrl);
        UrlEncodedFormEntity entity = new UrlEncodedFormEntity(
                Arrays.asList(new BasicNameValuePair("appName", currentAppName),new BasicNameValuePair("dependentAppNames", relyAppNames)));
        req.setEntity(entity);
        curHttpPost = req;

        try(CloseableHttpResponse resp = httpClient.execute(req)) {
            if(resp.getEntity() != null
                    && resp.getEntity().getContentType() != null
                    && resp.getEntity().getContentType().getValue() != null) {
                if(resp.getStatusLine().getStatusCode()== 404 || !resp.getEntity().getContentType().getValue().startsWith("text/event-stream")){
                    logger.info("eurekaServer 不支持SSE push");
                    Thread.sleep(60000);
                    return true;
                }
            }
            ByteArrayOutputStream buffer = new ByteArrayOutputStream(1024);
            byte[] bs = new byte[1024];
            while(true) {
                int length = resp.getEntity().getContent().read(bs);
                if (length == -1) {//end of stream
                    break;
                } else if(length > 0) {
                    buffer.write(bs, 0, length);
                    buffer = consumeBuffer(buffer);
                }
            }

            continuousIoExceptionTimes = 0;
        } catch (IOException e) {
            logger.info("连接eurekaServer失败，将会重试:{}", e.getMessage());
            //连续发生5次IOException，则sleep 10秒，以防止死循环耗尽cpu（所有eureka服务都挂掉时会死循环）
            if(continuousIoExceptionTimes++ >= 5){
                Thread.sleep(10000);
            }
        }
        return true;
    }

    private ByteArrayOutputStream consumeBuffer(ByteArrayOutputStream buffer) throws Exception {
        List<String> sseDataLines = getSseDateLines(buffer);

        Set<String> appNamesToRefresh = new HashSet<>();
        for(String sseDataLine : sseDataLines){
            if(sseDataLine.startsWith(SSE_PREFIX)){
                String jsonData = sseDataLine.substring(SSE_PREFIX.length(), sseDataLine.length()-2);//去掉尾部的\n\n
                JSONObject jsonObject = JSON.parseObject(jsonData);
                if(EUREKA_COMMAND_REFRESH.equals(jsonObject.getString("command"))){
                    //appNames 这个字段肯定是全部大写的
                    String appNames = jsonObject.getString("appNames");
                    appNamesToRefresh.addAll(Arrays.asList(appNames.split(",")));
                }
            }else{
                logger.warn("[NOTIFYME]invalid data,ignore it:{}", sseDataLine);
            }
        }
        if(!appNamesToRefresh.isEmpty()) {
            logger.info("notifyRefreshEureka: {}", appNamesToRefresh);
            notifyRefreshEureka(appNamesToRefresh);
        }

        return buffer;
    }

    private List<String> getSseDateLines(ByteArrayOutputStream buffer){
        List<String> sseDataLines = new ArrayList<>();
        boolean isAllDataLinesFetched = false;
        while(!isAllDataLinesFetched && buffer.size() > 1) {
            //这里要考虑多个 data:*\n\n 行出现的情况
            byte[] allBs = buffer.toByteArray();
            for (int i = 0; i < allBs.length - 1; i++) {
                if (allBs[i] == '\n' && i < allBs.length - 1 && allBs[i + 1] == '\n') {
                    String sseDataLine = new String(allBs, 0, i + 2);
                    sseDataLines.add(sseDataLine);

                    buffer.reset();
                    buffer.write(allBs, i + 2, allBs.length - (i + 2));
                    break;
                } else if( i >= allBs.length - 2){//遍历到末尾仍然没有发现匹配的\n\n则直接跳出循环
                    isAllDataLinesFetched = true;
                    break;
                }
            }
        }

        return sseDataLines;
    }

    /**
     * 让eureka客户端使用sse技术连接到eurekaServer上，当eurekaServer检测到服务器注册、取消注册等事件时，通过sse主动通知客户端，随后客户端刷新服务器列表。
     */
    private void notifyRefreshEureka(Set<String> appNamesToRefresh) throws Exception {
        //调用discoveryClient.refreshRegistry方法来触发刷新本地的服务器列表（会获取注册到eureka上的所有服务器）。
        EurekaClientUtils.refreshRegistry(eurekaClient);

        //对于需要刷新instances的应用（比如stock-service），调用对应的DynamicServerListLoadBalancer的updateListOfServers方法，触发更新服务器列表
        for(String serviceId : ribbonSpringClientFactory.getContextNames()) {
            if(appNamesToRefresh.contains(serviceId.toUpperCase())) {
                //从ribbon子容器中取得ILoadBalancer实例
                ILoadBalancer loadBalancer = ribbonSpringClientFactory.getInstance(serviceId, ILoadBalancer.class);
                if (loadBalancer instanceof DynamicServerListLoadBalancer) {
                    ((DynamicServerListLoadBalancer) loadBalancer).updateListOfServers();
                }
            }
        }

        //发送eureka状态变更事件
        applicationContext.publishEvent(new EurekaInstanceChangedEvent(applicationContext, appNamesToRefresh));
    }

    /**
     * 随机获取一个eurekaServer的地址
     * @return
     */
    private String getRandomEurekaServerUrl(){
        List<String> eurekaServerUrls = eurekaClient.getEurekaClientConfig().getEurekaServerServiceUrls("defaultZone");
        if(eurekaServerUrls == null || eurekaServerUrls.isEmpty()){
            return null;
        }
        return eurekaServerUrls.get(RandomUtils.nextInt(0, eurekaServerUrls.size()));
    }

}
