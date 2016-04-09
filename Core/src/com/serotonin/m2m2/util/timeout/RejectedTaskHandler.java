/**
 * Copyright (C) 2016 Infinite Automation Software. All rights reserved.
 * @author Terry Packer
 */
package com.serotonin.m2m2.util.timeout;

import com.serotonin.timer.RejectedTaskReason;

/**
 * @author Terry Packer
 *
 */
public interface RejectedTaskHandler {

	/**
	 * Task was rejected
	 * @param reason
	 */
	public void rejected(RejectedTaskReason reason);
}
