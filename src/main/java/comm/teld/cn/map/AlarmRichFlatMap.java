package comm.teld.cn.map;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;

import org.apache.flink.api.common.functions.FilterFunction;
import org.apache.flink.api.common.functions.RichFlatMapFunction;
import org.apache.flink.util.Collector;

import comm.teld.cn.common.RedisClient;
import comm.teld.cn.common.Utils;
import comm.teld.cn.common.config.ConfigBean;
import comm.teld.cn.event.AlarmDataEvent;
import comm.teld.cn.event.BaseDTO;
import comm.teld.cn.event.ErrorParseMessageEvent;
import comm.teld.cn.filter.CommMsg;
import comm.teld.cn.filter.DevMessageType;
import comm.teld.cn.log.LoggerUtils;
import monitor.protobuf.Monitorprotobuf.AlarmReq;
import redis.clients.jedis.Jedis;

//告警推送通知
public class AlarmRichFlatMap extends RichFlatMapFunction<CommMsg, BaseDTO> {
    private static final long serialVersionUID = 716179022840156379L;
    
    private final ConfigBean configBean;
    
    public AlarmRichFlatMap(ConfigBean configBean) {
        this.configBean=configBean;
    }

    @Override
    public void flatMap(CommMsg commMsg, Collector<BaseDTO> collector) throws Exception {
        Jedis jedis = null;
        try {
            AlarmReq alarmReq = null;
            try {
                alarmReq = AlarmReq.parseFrom(commMsg.Payload);
            } catch (Exception e1) {
                ErrorParseMessageEvent errorParseMessage = new ErrorParseMessageEvent();
                errorParseMessage.FieldFill(commMsg);
                System.out.println("==ERROR---AlarmRichFlatMap=PackageId=" + commMsg.PackageId + ",CtrlAddress="
                                + commMsg.CtrlAddress + ",FesSendTime=" + commMsg.FesSendTime + ", "
                                + Utils.bytesToHexString(commMsg.Payload));
                collector.collect(errorParseMessage);
                return;
            }
            
            AlarmDataEvent alarmEvent = new AlarmDataEvent();
            alarmEvent.FieldFill(commMsg);

            alarmEvent.CanIndex = alarmReq.getDevIndex();
            alarmEvent.SN = alarmReq.getSN();
            alarmEvent.DevDescType = alarmReq.getDevType().getNumber();
            alarmEvent.AlarmSendReason = alarmReq.getReason().getNumber();
            alarmEvent.AlarmTime = alarmReq.getAlarmTime().getTime();
            alarmEvent.AlarmReserved1 = alarmReq.getReserved1();
            alarmEvent.AlarmReserved2 = alarmReq.getReserved2();

            List<AlarmDataEvent.AlarmEventDetail> tempAlarmDetails = new ArrayList<>();
            alarmReq.getAlarmDataListList().forEach(alarmProtoDetail -> {
                AlarmDataEvent.AlarmEventDetail alarmEventDetail = new AlarmDataEvent.AlarmEventDetail();
                alarmEventDetail.Code = alarmProtoDetail.getAlarmCode();
                alarmEventDetail.State = alarmProtoDetail.getAlarmState().getNumber();
                alarmEventDetail.Reserved1 = alarmProtoDetail.getReserved1();
                alarmEventDetail.Reserved2 = alarmProtoDetail.getReserved2();
                tempAlarmDetails.add(alarmEventDetail);
            });
            alarmEvent.AlarmEventDetails = tempAlarmDetails;
            
            String hashId="Alarm:"+alarmEvent.SN;
            //TODO SN is ""
            
            HashMap<String, String> hashMap=new HashMap<String, String>();
            hashMap.put("CtrlAddr", alarmEvent.CtrlAddress);
            hashMap.put("CanIndex", String.valueOf(alarmEvent.CanIndex));
            hashMap.put("DeviceType", String.valueOf(alarmEvent.DevDescType));
            hashMap.put("AlarmTime", String.valueOf(alarmEvent.AlarmTime));
            hashMap.put("FlinkUpdateTime", Utils.millTimeToStr(System.currentTimeMillis()));
            hashMap.put("AlarmDataDetail", Utils.objectToJSON(alarmEvent.AlarmEventDetails));
            hashMap.put("Reserved1", alarmEvent.AlarmReserved1);
            hashMap.put("Reserved2", alarmEvent.AlarmReserved2);
            
            jedis=RedisClient.getJedis(configBean);
            List<String> stationInfo = RedisClient.getStationInfo(jedis, hashId);
            jedis.hmset(hashId, hashMap);
            
            alarmEvent.stationFieldFill(stationInfo);
            System.out.println("alarmEvent.SN:"+alarmEvent.SN);
//            Thread.sleep(10000);
           // System.out.println("告警数据:" + Utils.objectToJSON(alarmEvent));
            System.out.println("告警数据:" + alarmEvent.SN);
            //LoggerUtils.recordLogger.info("flink处理数据结果:"+Utils.objectToJSON(alarmEvent));
            collector.collect(alarmEvent);
        } finally {
            if (jedis != null) {
                jedis.close();
            }
        }
    }

    public static FilterFunction<CommMsg> filterFun = new FilterFunction<CommMsg>() {
        private static final long serialVersionUID = -3634906316441928256L;
        
        @Override
        public boolean filter(CommMsg commMsg) throws Exception {
        	AlarmReq alarmReq = AlarmReq.parseFrom(commMsg.Payload);
        	System.out.println("拉取数据"+alarmReq.getSN());
            return commMsg.MessageType == DevMessageType.ALARM_DATA;
        }
    };

}
