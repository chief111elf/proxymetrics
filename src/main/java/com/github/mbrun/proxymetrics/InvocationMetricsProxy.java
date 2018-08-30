package com.github.mbrun.proxymetrics;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * See doc at https://docs.oracle.com/javase/8/docs/technotes/guides/reflection/proxy.html
 */
public class InvocationMetricsProxy implements InvocationHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(InvocationMetricsProxy.class);
    private static final Map<Object, InvocationMetricsProxy> objectProxyMap = new ConcurrentHashMap<>();

    private Map<String, Map.Entry<Method, LongSummaryStatistics>> methodStats = new ConcurrentHashMap<>();
    private Object target;

    /**
     * @param obj
     * @param marker Object to be used as marker to later retrieve the measurements in form of an {@link InvocationMetricsProxy} object. This object must use a not changing proper implementation of {@code hashcode()} method as a hash based map is used in the background!
     * @param <T>
     * @return
     */
    @SuppressWarnings("unchecked")
    public static <T> T newInstance(T obj, Object marker) {
        InvocationMetricsProxy metricsProxy = new InvocationMetricsProxy(obj);
        T proxy = (T) java.lang.reflect.Proxy.newProxyInstance(
                obj.getClass().getClassLoader(),
                obj.getClass().getInterfaces(),
                metricsProxy);
        objectProxyMap.putIfAbsent(marker, metricsProxy);
        return proxy;
    }

    /**
     * @param marker
     * @return
     */
    public static Optional<InvocationMetricsProxy> getMetricsProxy(Object marker) {
        return Optional.ofNullable(objectProxyMap.get(marker));
    }

    /**
     * Utility method to log the statistics of a single {@link LongSummaryStatistics} object with a optional preliminary lines.
     *
     * @param statistics
     * @param additionals
     */
    public static void log(LongSummaryStatistics statistics, String... additionals) {
        LOGGER.info("++++++++++++++++++++++++++++++++++");
        for (String additional : additionals) {
            LOGGER.info(additional);
        }
        LOGGER.info("Count: {}", statistics.getCount());
        LOGGER.info("Min: {}", statistics.getMin());
        LOGGER.info("Max: {}", statistics.getMax());
        LOGGER.info("Avg: {}", statistics.getAverage());
    }

    private InvocationMetricsProxy(Object target) {
        this.target = target;
        for (Class<?> clazz : target.getClass().getInterfaces()) {
            for (Method method : clazz.getMethods()) {
                this.methodStats.putIfAbsent(method.toString(), new AbstractMap.SimpleEntry<>(method, new LongSummaryStatistics()));
            }
        }
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        Object result = null;
        try {
            long start = System.nanoTime();
            result = method.invoke(target, args);
            long elapsed = System.nanoTime() - start;
            Map.Entry<Method, LongSummaryStatistics> methodStatistics = methodStats.get(method.toString());
            if (null != methodStatistics) {
                methodStatistics.getValue().accept(elapsed);
            }
        } catch (Exception e) {
            LOGGER.error("Unexpected exception occurred: {}", e);
        }
        return result;
    }

    /**
     * @param methodName
     * @param containsSearch
     * @param stopOnFirstMatch
     * @return
     */
    public List<Map.Entry<String, LongSummaryStatistics>> getStatsForMethod(String methodName,
                                                                            boolean containsSearch, boolean stopOnFirstMatch) {
        List<Map.Entry<String, LongSummaryStatistics>> result = new ArrayList<>();
        for (Map.Entry<Method, LongSummaryStatistics> stats : methodStats.values()) {
            boolean hit = containsSearch ? stats.getKey().getName().contains(methodName) : stats.getKey().getName().equals(methodName);
            if (hit) {
                result.add(new AbstractMap.SimpleEntry<String, LongSummaryStatistics>(stats.getKey().toString(), stats.getValue()));
                if (stopOnFirstMatch) {
                    break;
                }
            }
        }
        return result;
    }

    /**
     * Logs the statistics of all methods.
     */
    public void logStats() {
        for (Map.Entry<Method, LongSummaryStatistics> stats : methodStats.values()) {
            InvocationMetricsProxy.log(stats.getValue(), "++++ Stats for " + stats.getKey() + " ++++");
        }
    }

    /**
     * Logs the statistics of a certain method.
     *
     * @param methodName Name of the method to log the statistics for. The given name must match exactly the method name.
     */
    public void logStats(String methodName) {
        logStats(methodName, false, true);
    }

    /**
     * Logs the statistics of one or more methods.
     *
     * @param methodName       Name of the method to log the statistics for. May be used to specify only parts of a method name. See {@code containsSearch} parameter.
     * @param containsSearch   Flag to define if the given {@code methodName} shall be used for a contains search among all the methods or not.
     * @param stopOnFirstMatch Flag to define if the log of statistics shall be stopped after finding the first matching method.
     */
    public void logStats(String methodName, boolean containsSearch, boolean stopOnFirstMatch) {
        List<Map.Entry<String, LongSummaryStatistics>> stats = getStatsForMethod(methodName, containsSearch, stopOnFirstMatch);
        for (Map.Entry<String, LongSummaryStatistics> singleStats : stats) {
            InvocationMetricsProxy.log(singleStats.getValue(), "++++ Stats for " + singleStats.getKey() + " ++++");
        }
    }
}