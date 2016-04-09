/*
    Copyright (C) 2014 Infinite Automation Systems Inc. All rights reserved.
    @author Matthew Lohbihler
 */
package com.serotonin.m2m2.rt.maint;

import java.util.Collection;
import java.util.concurrent.ThreadPoolExecutor;

import com.serotonin.m2m2.Common;
import com.serotonin.monitor.IntegerMonitor;
import com.serotonin.timer.FixedRateTrigger;
import com.serotonin.timer.TimerTask;

public class WorkItemMonitor extends TimerTask {
    private static final long TIMEOUT = 1000 * 10; // Run every ten seconds.

    /**
     * This method will set up the memory checking job. It assumes that the corresponding system setting for running
     * this job is true.
     */
    public static void start() {
        Common.timer.schedule(new WorkItemMonitor());
    }

    public static final String SCHEDULED_TASK_MONITOR_ID = WorkItemMonitor.class.getName() + ".scheduledTimerTaskCount";
    public static final String MEDIUM_PROIRITY_MONITOR_ID = WorkItemMonitor.class.getName()
            + ".mediumPriorityServiceQueueSize";
    public static final String HIGH_PROIRITY_MONITOR_ID = WorkItemMonitor.class.getName()
            + ".highPriorityServiceQueueSize";
    public static final String MAX_STACK_HEIGHT_MONITOR_ID = WorkItemMonitor.class.getName() + ".maxStackHeight";
    public static final String THREAD_COUNT_MONITOR_ID = WorkItemMonitor.class.getName() + ".threadCount";
    public static final String DB_ACTIVE_CONNECTIONS_MONITOR_ID = WorkItemMonitor.class.getName()
            + ".dbActiveConnections";
    public static final String DB_IDLE_CONNECTIONS_MONITOR_ID = WorkItemMonitor.class.getName() + ".dbIdleConnections";

    private final IntegerMonitor highPriorityServiceQueueSize = new IntegerMonitor(HIGH_PROIRITY_MONITOR_ID,
            "internal.monitor.MONITOR_HIGH");
    private final IntegerMonitor mediumPriorityServiceQueueSize = new IntegerMonitor(MEDIUM_PROIRITY_MONITOR_ID,
            "internal.monitor.MONITOR_MEDIUM");
    private final IntegerMonitor scheduledTimerTaskCount = new IntegerMonitor(SCHEDULED_TASK_MONITOR_ID,
            "internal.monitor.MONITOR_SCHEDULED");
    private final IntegerMonitor maxStackHeight = new IntegerMonitor(MAX_STACK_HEIGHT_MONITOR_ID,
            "internal.monitor.MONITOR_STACK_HEIGHT");
    private final IntegerMonitor threadCount = new IntegerMonitor(THREAD_COUNT_MONITOR_ID,
            "internal.monitor.MONITOR_THREAD_COUNT");
    private final IntegerMonitor dbActiveConnections = new IntegerMonitor(DB_ACTIVE_CONNECTIONS_MONITOR_ID,
            "internal.monitor.DB_ACTIVE_CONNECTIONS");
    private final IntegerMonitor dbIdleConnections = new IntegerMonitor(DB_IDLE_CONNECTIONS_MONITOR_ID,
            "internal.monitor.DB_IDLE_CONNECTIONS");


    private final IntegerMonitor javaMaxMemory = new IntegerMonitor("java.lang.Runtime.maxMemory",
            "java.monitor.JAVA_MAX_MEMORY");
    private final IntegerMonitor javaUsedMemory = new IntegerMonitor("java.lang.Runtime.usedMemory",
            "java.monitor.JAVA_USED_MEMORY");
    private final IntegerMonitor javaFreeMemory = new IntegerMonitor("java.lang.Runtime.freeMemory",
            "java.monitor.JAVA_FREE_MEMORY");
    private final IntegerMonitor javaAvailableProcessors = new IntegerMonitor("java.lang.Runtime.availableProcessors",
            "java.monitor.JAVA_PROCESSORS");
    
    private final int mb = 1024*1024;
    
    private boolean running;
    
    private WorkItemMonitor() {
        super(new FixedRateTrigger(TIMEOUT, TIMEOUT));
        this.running = true;
        Common.MONITORED_VALUES.addIfMissingStatMonitor(highPriorityServiceQueueSize);
        Common.MONITORED_VALUES.addIfMissingStatMonitor(mediumPriorityServiceQueueSize);
        Common.MONITORED_VALUES.addIfMissingStatMonitor(scheduledTimerTaskCount);
        Common.MONITORED_VALUES.addIfMissingStatMonitor(maxStackHeight);
        Common.MONITORED_VALUES.addIfMissingStatMonitor(threadCount);
        Common.MONITORED_VALUES.addIfMissingStatMonitor(dbActiveConnections);
        Common.MONITORED_VALUES.addIfMissingStatMonitor(dbIdleConnections);
        Common.MONITORED_VALUES.addIfMissingStatMonitor(javaFreeMemory);
        Common.MONITORED_VALUES.addIfMissingStatMonitor(javaMaxMemory);
        Common.MONITORED_VALUES.addIfMissingStatMonitor(javaAvailableProcessors);

        
        //Set the available processors, we don't need to poll this
        javaAvailableProcessors.setValue(Runtime.getRuntime().availableProcessors());
                
    }

    @Override
    public void run(long fireTime) {
    	if(!running)
    		return;
        check();
    }

    public void check() {
    	
    	if(Common.timer != null){
    		highPriorityServiceQueueSize.setValue(((ThreadPoolExecutor) Common.timer.getExecutorService()).getActiveCount());
    		scheduledTimerTaskCount.setValue(Common.timer.size());
    	}
    	if(Common.backgroundProcessing != null)
    		mediumPriorityServiceQueueSize.setValue(Common.backgroundProcessing.getMediumPriorityServiceQueueSize());
       

        // Check the stack heights
        int max = 0;
        Collection<StackTraceElement[]> stacks = Thread.getAllStackTraces().values();
        int count = stacks.size();
        for (StackTraceElement[] stack : stacks) {
            if (max < stack.length)
                max = stack.length;
            if (stack.length == 0)
                // Don't include inactive threads
                count--;
        }
        threadCount.setValue(count);
        maxStackHeight.setValue(max);

        if(Common.databaseProxy != null){
	        dbActiveConnections.setValue(Common.databaseProxy.getActiveConnections());
	        dbIdleConnections.setValue(Common.databaseProxy.getIdleConnections());
        }
        
        //In MB
        Runtime rt = Runtime.getRuntime();
        javaMaxMemory.setValue((int)(rt.maxMemory()/mb));
        javaUsedMemory.setValue((int)(rt.totalMemory()/mb) -(int)(rt.freeMemory()/mb));   
        javaFreeMemory.setValue(javaMaxMemory.intValue() - javaUsedMemory.intValue());
    }
    
    /* (non-Javadoc)
     * @see com.serotonin.timer.TimerTask#cancel()
     */
    @Override
    public boolean cancel() {
    	this.running = false;
    	return super.cancel();
    }
}
