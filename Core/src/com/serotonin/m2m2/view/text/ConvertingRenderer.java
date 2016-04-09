/*
 * Copyright (C) 2013 Deltamation Software. All rights reserved.
 * @author Jared Wiltshire
 */

package com.serotonin.m2m2.view.text;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;

import javax.measure.unit.Unit;

import com.serotonin.json.JsonException;
import com.serotonin.json.JsonReader;
import com.serotonin.json.ObjectWriter;
import com.serotonin.json.type.JsonObject;
import com.serotonin.m2m2.i18n.TranslatableJsonException;
import com.serotonin.m2m2.util.UnitUtil;
import com.serotonin.util.SerializationHelper;

/**
 * Copyright (C) 2013 Deltamation Software. All rights reserved.
 * @author Jared Wiltshire
 */
public abstract class ConvertingRenderer extends BaseTextRenderer {
    
    protected boolean useUnitAsSuffix;
    protected Unit<?> unit;
    protected Unit<?> renderedUnit;
    
    public ConvertingRenderer() {
        setDefaults();
    }
    
    /**
     * Sets fields to defaults, called from serialization readObject()
     * and constructor
     */
    protected void setDefaults() {
        useUnitAsSuffix = true; 
        unit = Unit.ONE;
        renderedUnit = Unit.ONE;
    }

    public Unit<?> getUnit() {
        return unit;
    }

    public void setUnit(Unit<?> unit) {
        this.unit = unit;
    }

    public Unit<?> getRenderedUnit() {
        return renderedUnit;
    }

    public void setRenderedUnit(Unit<?> renderedUnit) {
        this.renderedUnit = renderedUnit;
    }
    
    public boolean isUseUnitAsSuffix() {
        return useUnitAsSuffix;
    }

    public void setUseUnitAsSuffix(boolean useUnit) {
        this.useUnitAsSuffix = useUnit;
    }

    private static final long serialVersionUID = -1L;
    private static final int version = 2;

    private void writeObject(ObjectOutputStream out) throws IOException {
        out.writeInt(version);
        out.writeBoolean(useUnitAsSuffix);
        if(unit != null)
        	SerializationHelper.writeSafeUTF(out, UnitUtil.formatUcum(unit));
        else
        	SerializationHelper.writeSafeUTF(out, UnitUtil.formatUcum(Unit.ONE));
        if(renderedUnit != null)
        	SerializationHelper.writeSafeUTF(out, UnitUtil.formatUcum(renderedUnit));
        else
        	SerializationHelper.writeSafeUTF(out, UnitUtil.formatUcum(Unit.ONE));
    }

    private void readObject(ObjectInputStream in) throws IOException {
        int ver = in.readInt(); // Read the version.
        //Version 1 did nothing
        if(ver == 2){
            useUnitAsSuffix = in.readBoolean();
            try{
            	unit = UnitUtil.parseUcum(SerializationHelper.readSafeUTF(in));
            }catch(Exception e){
            	unit = Unit.ONE;
            }
            try{
            	renderedUnit = UnitUtil.parseUcum(SerializationHelper.readSafeUTF(in));
            }catch(Exception e){
            	renderedUnit = Unit.ONE;
            }
       }
    }

    @Override
    public void jsonWrite(ObjectWriter writer) throws IOException, JsonException {
        super.jsonWrite(writer);
        
        writer.writeEntry("useUnitAsSuffix", useUnitAsSuffix);
        
        writer.writeEntry("unit", UnitUtil.formatUcum(unit));
        //if (!renderedUnit.equals(unit))
        writer.writeEntry("renderedUnit", UnitUtil.formatUcum(renderedUnit));
        
    }
    
    @Override
    public void jsonRead(JsonReader reader, JsonObject jsonObject) throws JsonException {
        super.jsonRead(reader, jsonObject);
        
        //To ensure we have this property as it is a new one
        if(jsonObject.containsKey("useUnitAsSuffix")){
        	useUnitAsSuffix = jsonObject.getBoolean("useUnitAsSuffix");
        }
        
        
        String text = jsonObject.getString("unit");
        if (text != null) {
            try {
                unit = UnitUtil.parseUcum(text);
            }
            catch (Exception e) {
                throw new TranslatableJsonException("emport.error.parseError", "unit");
            }
        }
        
        text = jsonObject.getString("renderedUnit");
        if (text != null) {
            try {
                renderedUnit = UnitUtil.parseUcum(text);
            }
            catch (Exception e) {
                throw new TranslatableJsonException("emport.error.parseError", "renderedUnit");
            }
        }
        else {
            renderedUnit = unit;
        }        
    }
}
