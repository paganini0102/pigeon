package com.dianping.pigeon.governor.monitor.load;

import com.dianping.cat.consumer.cross.model.entity.CrossReport;
import com.dianping.cat.consumer.cross.model.entity.Local;
import com.dianping.cat.consumer.cross.model.entity.Remote;
import com.dianping.lion.client.region.RegionManager;
import com.dianping.lion.client.region.RegionManagerLoader;
import com.dianping.pigeon.config.ConfigManager;
import com.dianping.pigeon.config.ConfigManagerLoader;
import com.dianping.pigeon.governor.message.MessageService;
import com.dianping.pigeon.governor.message.impl.MessageServiceImpl;
import com.dianping.pigeon.governor.monitor.load.message.ClientSkewMessage;
import com.dianping.pigeon.governor.monitor.load.message.ServerSkewMessage;
import com.dianping.pigeon.governor.util.CatReportXMLUtils;
import com.dianping.pigeon.governor.util.Constants;
import com.dianping.pigeon.governor.util.GsonUtils;
import com.dianping.pigeon.governor.util.HttpCallUtils;
import org.apache.commons.lang.StringUtils;

import java.util.*;


/**
 * Created by shihuashen on 16/7/12.
 */
public class CrossAnalyze implements Runnable{
    private ConfigManager configManager;
    private CrossReport crossReport;
    private ServerClientDataComparator comparator;
    private BalanceAnalyzer analyzer;
    private String projectName;
    private String dateTime;
    private String url;
    private MessageService messageService;
    private RegionManager regionManager = RegionManagerLoader.getRegionManager();
    public CrossAnalyze(String projectName,
                        String dateTime,
                        ServerClientDataComparator comparator,
                        BalanceAnalyzer analyzer,
                        MessageService messageService
                        ){
        this.projectName = StringUtils.trim(projectName);
        this.dateTime = dateTime;
        this.configManager = ConfigManagerLoader.getConfigManager();
        this.url =  getCatAddress()+"cat/r/cross?domain="+projectName+"&ip=All&date="+dateTime;
        this.comparator = comparator;
        this.analyzer = analyzer;
        this.messageService = messageService;
    }
    @Override
    public void run() {
        this.crossReport = getCrossReport(this.projectName,this.dateTime);
        serverAndClientCountCheck(this.crossReport);
        loadBalanceCheck(this.crossReport);
    }




    private CrossReport getCrossReport(String projectName,String dateTime){
        String url = getCatAddress()+"cat/r/cross?domain="+projectName+"&ip=All&date="+dateTime+"&forceDownload=xml";
        String xml = HttpCallUtils.httpGet(url);
        return CatReportXMLUtils.XMLToCrossReport(xml);
    }
    private String getCatAddress(){
        return Constants.onlineCatAddress;
//        String env = configManager.getEnv();
//        if(env.equals("qa"))
//            return Constants.qaCatAddress;
//        if(env.equals("prelease"))
//            return Constants.ppeCatAddress;
//        return Constants.onlineCatAddress;
    }
    //扫描CrossReport,确认服务端收到的调用请求和客户端汇总的调用请求数是否一致.
    public void serverAndClientCountCheck(CrossReport report){
        Map<String,Local> locals = report.getLocals();
        for(Iterator<String> iterator = locals.keySet().iterator(); iterator.hasNext();){
            Local local = locals.get(iterator.next());
            Map<String,Remote> remotes = local.getRemotes();
            Map<String,Long> tmpMap = new HashMap<String,Long>();
            for(Iterator<String> iter = remotes.keySet().iterator();iter.hasNext();){
                Remote remote = remotes.get(iter.next());
                String role = remote.getRole();
                String ip = remote.getIp();
                if(role.equals("Pigeon.Caller")||role.equals("Pigeon.Client")){
                    if(tmpMap.containsKey(ip)){
                        long count =  tmpMap.get(ip);
                        if(!this.comparator.compare(remote.getType().getTotalCount(),count)){
//                            System.out.println(remote.getApp()+":"+ip+" count1 as :"+remote.getType().getTotalCount()
//                                    +" count2 as :"+count);
//                            tmpMap.remove(ip);
                        }
                    }else{
                        tmpMap.put(ip,remote.getType().getTotalCount());
                    }
                }
            }
        }
    }
    class AnalyzeResult{
        private long hostAccessCount;
        private Map<String,Long> projectsAccessCount;
        private String ip ;
        AnalyzeResult(Local local){
            this.ip = local.getId();
            this.projectsAccessCount = new HashMap<String,Long>();
            Map<String,Remote> remoteMap = local.getRemotes();
            for(Iterator<String> iterator = remoteMap.keySet().iterator();iterator.hasNext();){
                Remote remote = remoteMap.get(iterator.next());
                if(remote.getRole().equals("Pigeon.Caller")){
                    String  projectName = remote.getApp();
                    long count = remote.getType().getTotalCount();
                    hostAccessCount+=count;
                    addProjectAccess(projectName,count);
                }
            }
        }
        private void addProjectAccess(String projectName,long count){
            if(this.projectsAccessCount.containsKey(projectName)){
                long tmp = this.projectsAccessCount.get(projectName);
                this.projectsAccessCount.put(projectName,tmp+count);
            }else{
                this.projectsAccessCount.put(projectName,count);
            }
        }

        public long getHostAccessCount() {
            return hostAccessCount;
        }

        public void setHostAccessCount(long hostAccessCount) {
            this.hostAccessCount = hostAccessCount;
        }

        public Map<String, Long> getProjectsAccessCount() {
            return projectsAccessCount;
        }

        public void setProjectsAccessCount(Map<String, Long> projectsAccessCount) {
            this.projectsAccessCount = projectsAccessCount;
        }
    }



    private  void loadBalanceCheck(CrossReport crossReport){
        Map<String,Local> locals = crossReport.getLocals();
        Map<String,AnalyzeResult> results = new HashMap<String,AnalyzeResult>();
        for(Iterator<Local> iterator = locals.values().iterator();iterator.hasNext();){
            Local local = iterator.next();
            results.put(local.getId(),new AnalyzeResult(local));
        }
        Map<String,Long> serverFlowDistribute = new HashMap<String, Long>();
        Map<String,Map<String,Long>> clientFlowDistribute = new HashMap<String,Map<String,Long>>();
        for(Iterator<AnalyzeResult> iterator = results.values().iterator();iterator.hasNext();){
            AnalyzeResult analyzeResult = iterator.next();
            serverFlowDistribute.put(analyzeResult.ip,analyzeResult.getHostAccessCount());
            for(Iterator<String> projectNameIter = analyzeResult.getProjectsAccessCount().keySet().iterator();
                    projectNameIter.hasNext();) {
                String projectName = projectNameIter.next();
                if(clientFlowDistribute.containsKey(projectName))
                    clientFlowDistribute.get(projectName).put(analyzeResult.ip,
                            analyzeResult.getProjectsAccessCount().get(projectName));
                else {
                    Map<String, Long> data = new HashMap<String, Long>();
                    data.put(analyzeResult.ip, analyzeResult.getProjectsAccessCount().get(projectName));
                    clientFlowDistribute.put(projectName, data);
                }
            }
        }
        if(!regionFlowAnalyze(serverFlowDistribute,analyzer)){
            ServerSkewMessage serverSkewMessage = new ServerSkewMessage();
            serverSkewMessage.setProjectName(this.projectName);
            serverSkewMessage.setFlowDistributed(serverFlowDistribute);
            serverSkewMessage.setUrl(this.url);
            serverSkewMessage.setCreateTime();
            messageService.sendMessage(serverSkewMessage);
        }
        for(Iterator<String> iterator = clientFlowDistribute.keySet().iterator();iterator.hasNext();){
            String projectName = iterator.next();
            if(!regionFlowAnalyze(clientFlowDistribute.get(projectName),analyzer)){
                ClientSkewMessage clientSkewMessage = new ClientSkewMessage();
                clientSkewMessage.setServerProjectName(this.projectName);
                clientSkewMessage.setClientProjectName(projectName);
                clientSkewMessage.setFlowDistributed(clientFlowDistribute.get(projectName));
                clientSkewMessage.setUrl(this.url);
                clientSkewMessage.setCreatTime();
                messageService.sendMessage(clientSkewMessage);
            }
        }

    }


    private boolean regionFlowAnalyze(Map<String,Long> data,BalanceAnalyzer analyzer){
        Map<String,List<Long>> regionGroups = new HashMap<String,List<Long>>();
        for(Iterator<String> iterator = data.keySet().iterator();
                iterator.hasNext();){
            String ipAddress = iterator.next();
            String regionName = regionManager.getRegion(ipAddress);
            if(regionName==null){
                System.out.println(ipAddress);
                System.out.println("Critical Error. Ip address region belonging error!!!!");
                return false;
            }
            if(regionGroups.containsKey(regionName)){
                regionGroups.get(regionName).add(data.get(ipAddress));
            }else{
                List<Long> list = new LinkedList<Long>();
                list.add(data.get(ipAddress));
                regionGroups.put(regionName,list);
            }
        }
        for(Iterator<String> iterator  = regionGroups.keySet().iterator();
                iterator.hasNext();){
            String regionName = iterator.next();
            if(!analyzer.balanceAnalysis(regionGroups.get(regionName)))
                return false;
        }
        return true;
    }
}