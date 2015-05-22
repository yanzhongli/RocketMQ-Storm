package com.alibaba.rocketmq.storm.bolt;

import backtype.storm.task.OutputCollector;
import backtype.storm.task.TopologyContext;
import backtype.storm.topology.IRichBolt;
import backtype.storm.topology.OutputFieldsDeclarer;
import backtype.storm.tuple.Tuple;
import com.alibaba.fastjson.JSON;
import com.alibaba.rocketmq.common.message.MessageExt;
import com.alibaba.rocketmq.storm.model.CRLog;
import com.alibaba.rocketmq.storm.redis.CacheManager;
import com.alibaba.rocketmq.storm.redis.Constant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.Charset;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.HashMap;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * <p>
 *     To aggregate CR grouping by offer ID, affiliate ID, event code.
 * </p>
 *
 * @version 1.0
 * @since 1.0
 * @author Li Zhanhui
 */
public class CRAggregationBolt implements IRichBolt, Constant {
    private static final long serialVersionUID = 7591260982890048043L;

    private static final Logger LOG = LoggerFactory.getLogger(CRAggregationBolt.class);

    private OutputCollector collector;

    private static final String DATE_FORMAT = "yyyyMMddHHmmss";

    private AtomicReference<HashMap<String, HashMap<String, HashMap<String, Long>>>>
            atomicReference = new AtomicReference<>();

    private AtomicLong counter = new AtomicLong(1L);

    private volatile boolean stop = false;

    @Override
    public void prepare(@SuppressWarnings("rawtypes") Map stormConf, TopologyContext context,
                        OutputCollector collector) {
        this.collector = collector;

        /**
         * We use one worker, one executor and one task strategy.
         */
        HashMap<String /* offer_id */, HashMap<String /* affiliate_id*/, HashMap<String /* event_code*/, Long>>>
                resultMap = new HashMap<>();

        while (!atomicReference.compareAndSet(null, resultMap)) {
        }

        Thread persistThread = new Thread(new PersistTask());
        persistThread.setName("PersistThread");
        persistThread.setDaemon(true);
        persistThread.start();
    }

    @Override
    public void execute(Tuple input) {

        if (counter.incrementAndGet() % 10000 == 0) {
            LOG.info("10000 tuples aggregated.");
        }

        Object msgObj = input.getValue(0);
        Object msgStat = input.getValue(1);
        try {
            if (msgObj instanceof MessageExt) {
                MessageExt msg = (MessageExt) msgObj;
                CRLog logEntry = JSON.parseObject(new String(msg.getBody(), Charset.forName("UTF-8")), CRLog.class);
                HashMap<String, HashMap<String, HashMap<String, Long>>> map = atomicReference.get();
                if (!map.containsKey(logEntry.getOffer_id())) {
                    HashMap<String, HashMap<String, Long>> affMap = new HashMap<>();
                    HashMap<String, Long> eventMap = new HashMap<>();
                    eventMap.put(logEntry.getEvent_code(), BASE);
                    affMap.put(logEntry.getAffiliate_id(), eventMap);
                    map.put(logEntry.getOffer_id(), affMap);
                } else {
                    HashMap<String, HashMap<String, Long>> affMap = map.get(logEntry.getOffer_id());
                    if (affMap.containsKey(logEntry.getAffiliate_id())) {
                        HashMap<String, Long> eventMap = affMap.get(logEntry.getAffiliate_id());
                        String eventCode = logEntry.getEvent_code();
                        if (eventMap.containsKey(eventCode)) {
                            eventMap.put(eventCode, eventMap.get(eventCode) + INCREMENT);
                        } else {
                            eventMap.put(logEntry.getEvent_code(), BASE);
                        }
                    } else {
                        HashMap<String, Long> eventMap = new HashMap<>();
                        eventMap.put(logEntry.getEvent_code(), BASE);
                        affMap.put(logEntry.getAffiliate_id(), eventMap);
                    }
                }
            } else {
                LOG.error("The first value in tuple should be MessageExt object");
            }
        } catch (Exception e) {
            LOG.error("Failed to handle Message", e);
            collector.fail(input);
            return;
        }

        collector.ack(input);
    }

    @Override
    public void cleanup() {
        stop = true;
    }

    @Override
    public void declareOutputFields(OutputFieldsDeclarer declarer) {
    }

    @Override
    public Map<String, Object> getComponentConfiguration() {
        return null;
    }


    class PersistTask implements Runnable {
        private final CacheManager cacheManager = CacheManager.getInstance();

        public PersistTask() {
        }

        @Override
        public void run() {
            while (!stop) {
                LOG.info("Start to persist aggregation result.");
                try {
                    HashMap<String, HashMap<String, HashMap<String, Long>>> map =
                            atomicReference.getAndSet(new HashMap<String, HashMap<String, HashMap<String, Long>>>());

                    if (null == map || map.isEmpty()) {
                        continue;
                    }

                    Calendar calendar = Calendar.getInstance();
                    //Use Beijing Time Zone: GMT+8
                    calendar.setTimeZone(TimeZone.getTimeZone("GMT+8"));
                    DateFormat dateFormatter = new SimpleDateFormat(DATE_FORMAT);

                    //TODO persist map
                    for (Map.Entry<String, HashMap<String, HashMap<String, Long>>> row : map.entrySet()) {
                        String offerId = row.getKey();
                        HashMap<String, HashMap<String, Long>> affMap = row.getValue();
                        for (Map.Entry<String, HashMap<String, Long>> affRow : affMap.entrySet()) {
                            String affId = affRow.getKey();
                            HashMap<String, Long> eventMap = affRow.getValue();
                            String key = offerId + "_" + affId + "_" +  dateFormatter.format(calendar.getTime());
                            StringBuilder click = new StringBuilder();
                            click.append("{");

                            StringBuilder conversion = new StringBuilder();
                            conversion.append("{");
                            for (Map.Entry<String, Long> eventRow : eventMap.entrySet()) {
                                String event = eventRow.getKey();
                                if (event.startsWith("C")) {
                                    click.append(event).append(": ").append(eventRow.getValue()).append(", ");
                                } else {
                                    conversion.append(event).append(": ").append(eventRow.getValue()).append(", ");
                                }
                            }

                            if (click.length() > 2) {
                                click.replace(click.length() - 2, click.length(), "}");
                            } else {
                                click.append("}");
                            }

                            if (conversion.length() > 2) {
                                conversion.replace(conversion.length() - 2, conversion.length(), "}");
                            } else {
                                conversion.append("}");
                            }

                            LOG.info("[Click] Key = " + click.toString());
                            LOG.info("[Conversion] Key = " + conversion.toString());
                            cacheManager.setKeyLive(key, PERIOD * NUMBERS, "{click: " + click + ", conversion: " + conversion + "}");
                        }
                    }
                    LOG.info("Persisting aggregation result done.");
                } catch (Exception e) {
                    LOG.error("Persistence of aggregated result failed.", e);
                } finally {
                    try {
                        Thread.sleep(PERIOD * 1000);
                    } catch (InterruptedException e) {
                        LOG.error("PersistThread was interrupted.", e);
                    }
                }
            }
        }
    }
}