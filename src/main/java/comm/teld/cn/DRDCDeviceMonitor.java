package comm.teld.cn;

import java.io.InputStream;
import java.util.Arrays;
import java.util.Properties;

import org.apache.flink.configuration.ConfigConstants;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.streaming.api.CheckpointingMode;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.connectors.kafka.FlinkKafkaConsumer08;
import org.apache.flink.streaming.connectors.kafka.FlinkKafkaProducer08;
import org.apache.flink.streaming.connectors.rabbitmq.RMQSink;
import org.apache.flink.streaming.connectors.rabbitmq.common.RMQConnectionConfig;
import org.apache.kafka.clients.producer.ProducerConfig;

import comm.teld.cn.common.Utils;
import comm.teld.cn.common.config.ConfigBean;
import comm.teld.cn.common.config.LoadPropertiesFile;
import comm.teld.cn.event.BaseDTO;
import comm.teld.cn.filter.CommMsg;
import comm.teld.cn.log.LoggerUtils;
import comm.teld.cn.map.AlarmRichFlatMap;
import comm.teld.cn.sink.ByteArrayDeserialization;
import comm.teld.cn.sink.EORMQSink;
import comm.teld.cn.sink.JsonDTOSerializing;

public class DRDCDeviceMonitor {

	private static void startProcessMessage(ConfigBean configBean) throws Exception {
		StreamExecutionEnvironment env=null;

		if(configBean.environmentDataCenter.startsWith("dev")) {
			Configuration localConfig = new Configuration();
			localConfig.setBoolean(ConfigConstants.LOCAL_START_WEBSERVER, true);
			env = StreamExecutionEnvironment.createLocalEnvironmentWithWebUI(localConfig);
			env.setParallelism(1);
		} else {
			env = StreamExecutionEnvironment.getExecutionEnvironment();
			//        	Configuration localConfig = new Configuration();
			//            localConfig.setBoolean(ConfigConstants.LOCAL_START_WEBSERVER, true);
			//        	env = StreamExecutionEnvironment.createLocalEnvironmentWithWebUI(localConfig);
			env.setParallelism(1);
		}
		env.enableCheckpointing(configBean.checkpointDuration,CheckpointingMode.EXACTLY_ONCE);
		// set mode to exactly-once (this is the default)
		//env.getCheckpointConfig().setCheckpointingMode(CheckpointingMode.EXACTLY_ONCE);
		//TODO
		if(!configBean.environmentDataCenter.startsWith("prod")) {
			env.disableOperatorChaining();
		}

		FlinkKafkaConsumer08<byte[]> devMonitorConsumer = new FlinkKafkaConsumer08<>(
				configBean.kafkaDeviceMonitorSourceTopic, new ByteArrayDeserialization(),
				configBean.kafkaDeviceMonitorSourceProperties);
		//devMonitorConsumer.setCommitOffsetsOnCheckpoints(true);
		// devMonitorConsumer.setStartFromGroupOffsets();
		DataStream<CommMsg> devMonitorDataStream = env.addSource(devMonitorConsumer).name("devMonitor").rebalance()
				.map(Utils.devMonitorMsgMapFunction).name("devMonitorCommMsg");
		
			convertToEvent(devMonitorDataStream, configBean);
	


		env.execute("MonitorDevice_" + configBean.environmentDataCenter);
	}

	private static void convertToEvent(DataStream<CommMsg> devMonitorDataStream, ConfigBean configBean) {
		//        devMonitorDataStream.filter(Utils.filterFun).name("nullFilterOne").map(new DevRuningMonitorRichMap())
		//                        .name("redisMapOne").addSink(myProducer).name("sinkCloseOne");

		configBean.kafkaDeviceMonitorSinkProperties.setProperty(ProducerConfig.CLIENT_ID_CONFIG,  "DRDCDMProducer" + java.util.UUID.randomUUID());
		FlinkKafkaProducer08<BaseDTO> myProducer = new FlinkKafkaProducer08<BaseDTO>(configBean.kafkaDeviceMonitorSinkTopic, new JsonDTOSerializing<BaseDTO>(), configBean.kafkaDeviceMonitorSinkProperties);

		final RMQConnectionConfig connectionConfig = new RMQConnectionConfig.Builder()
				.setAutomaticRecovery(true)
				.setNetworkRecoveryInterval(5000)
				.setHost(configBean.rMQDeviceMonitorSinkHost)
				.setPort(configBean.rMQDeviceMonitorSinkPort)
				.setUserName(configBean.rMQDeviceMonitorSinkUername)
				.setPassword(configBean.rMQDeviceMonitorSinkPassword)
				.setVirtualHost(configBean.rMQDeviceMonitorSinkVirtualHost)
				.build();

		RMQSink<BaseDTO> rmqSink = new EORMQSink<>(connectionConfig, configBean.rMQDeviceMonitorSinkQueueName, new JsonDTOSerializing<BaseDTO>());

		// 告警数据通知. 0x58
		devMonitorDataStream.filter(AlarmRichFlatMap.filterFun).name("alarmFilter")
		.flatMap(new AlarmRichFlatMap(configBean)).name("alarmFlatMap")
		.addSink(rmqSink).name("devMonitorSink");
		//        // 直流模块数据通知. 0x60
		//        devMonitorDataStream.filter(DCModularRichFlatMap.filterFun).name("dcModularFilter")
		//                        .flatMap(new DCModularRichFlatMap(configBean)).name("dcModularFlatMap")
		//                        .addSink(myProducer).name("devMonitorSink");
		//        // 交流模块数据通知. 0x62
		//        devMonitorDataStream.filter(ACModularRichFlatMap.filterFun).name("acModularFilter")
		//                        .flatMap(new ACModularRichFlatMap(configBean)).name("acModularFlatMap")
		//                        .addSink(myProducer).name("devMonitorSink");
		//        // PDU数据通知. 0x64
		//        devMonitorDataStream.filter(PDURichFlatMap.filterFun).name("pduModularFilter")
		//                        .flatMap(new PDURichFlatMap(configBean)).name("pduModularFlatMap")
		//                        .addSink(myProducer).name("devMonitorSink");
		//        // CCU数据通知. 0x66
		//        devMonitorDataStream.filter(CCURichFlatMap.filterFun).name("ccuModularFilter")
		//                        .flatMap(new CCURichFlatMap(configBean)).name("ccuModularFlatMap")
		//                        .addSink(myProducer).name("devMonitorSink");

	}

	public static void main(String[] args) {
		try {
			if (args.length != 1) {
				System.err.println("String[] args " + Arrays.asList(args) + "error, args.length()=" + args.length);
				System.err.println("使用args[0]指定配置文件名字前缀");
				return;
			}

			//加载配置文件 change by wangwb   
			InputStream in = DRDCDeviceMonitor.class.getResourceAsStream("/" + args[0].trim() + ".properties");
			Properties prop = new Properties();
			prop.load(in);
			//日志初始化 change by wangwb
			LoggerUtils.initSysLogger(prop);
			LoggerUtils.initRecordLogger(prop);

			LoadPropertiesFile loadPropertiesFile = new LoadPropertiesFile(args[0]);
			ConfigBean configBean = loadPropertiesFile.getConfigBean();
			System.out.println("配置文件:"+configBean.toString());
			LoggerUtils.sysLogger.info("配置文件:"+configBean.toString());
			startProcessMessage(configBean);
		} catch (Exception e) {
			e.printStackTrace();
		}
	}


}
