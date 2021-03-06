/**
 * Copyright (C) 2015 Infinite Automation Software. All rights reserved.
 * @author Terry Packer
 */
package com.serotonin.m2m2.web.mvc.rest.v1.model.dataPoint;

import com.fasterxml.jackson.annotation.JsonGetter;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.serotonin.m2m2.DataTypes;
import com.serotonin.m2m2.vo.dataSource.PointLocatorVO;
import com.serotonin.m2m2.web.mvc.rest.v1.csv.CSVColumnGetter;
import com.serotonin.m2m2.web.mvc.rest.v1.csv.CSVColumnSetter;
import com.serotonin.m2m2.web.mvc.rest.v1.csv.CSVEntity;
import com.serotonin.m2m2.web.mvc.rest.v1.model.AbstractRestModel;

/**
 * @author Terry Packer
 *
 */
@CSVEntity(derived=true)
public abstract class PointLocatorModel<T extends PointLocatorVO> extends AbstractRestModel<T>{
	
	/**
	 * @param data
	 */
	public PointLocatorModel(T data) {
		super(data);
	}

    @JsonGetter("modelType")
    public abstract String getTypeName();
   
    @JsonSetter("modelType")
    public void setTypeName(String typeName){ }
    
    
	@JsonGetter("dataType")
	@CSVColumnGetter(order=15, header="dataType")
	public String getDataTypeId() {
	    return DataTypes.CODES.getCode(this.data.getDataTypeId());
	}

	@JsonSetter("dataType")
	@CSVColumnSetter(order=15, header="dataType")
	public void setDataTypeId(String dataType) { } //No Op

	@JsonGetter("settable")
	@CSVColumnGetter(order=16, header="settable")
	public boolean isSettable() {
	    return this.data.isSettable();
	}

	@JsonSetter("settable")
	@CSVColumnSetter(order=16, header="settable")
	public void setSettable(boolean settable) { } //No Op
	
	@CSVColumnGetter(order=17, header="relinquishable")
    @JsonGetter("relinquishable")
    public boolean isRelinquishable(){
    	return this.data.isRelinquishable();
    }
	@CSVColumnSetter(order=17, header="relinquishable")
    @JsonGetter("relinquishable")
    public void setRelinquishable(boolean relinquishable){
    	
    }
}
