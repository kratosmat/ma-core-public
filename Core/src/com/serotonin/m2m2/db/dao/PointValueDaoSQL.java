/*
    Copyright (C) 2014 Infinite Automation Systems Inc. All rights reserved.
    @author Matthew Lohbihler
 */
package com.serotonin.m2m2.db.dao;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.RejectedExecutionException;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.perf4j.StopWatch;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.RowMapper;

import com.serotonin.ShouldNeverHappenException;
import com.serotonin.db.MappedRowCallback;
import com.serotonin.db.spring.ExtendedJdbcTemplate;
import com.serotonin.io.StreamUtils;
import com.serotonin.m2m2.Common;
import com.serotonin.m2m2.DataTypes;
import com.serotonin.m2m2.ImageSaveException;
import com.serotonin.m2m2.db.DatabaseProxy.DatabaseType;
import com.serotonin.m2m2.i18n.TranslatableMessage;
import com.serotonin.m2m2.rt.dataImage.AnnotatedPointValueTime;
import com.serotonin.m2m2.rt.dataImage.IdPointValueTime;
import com.serotonin.m2m2.rt.dataImage.PointValueTime;
import com.serotonin.m2m2.rt.dataImage.SetPointSource;
import com.serotonin.m2m2.rt.dataImage.types.AlphanumericValue;
import com.serotonin.m2m2.rt.dataImage.types.BinaryValue;
import com.serotonin.m2m2.rt.dataImage.types.DataValue;
import com.serotonin.m2m2.rt.dataImage.types.ImageValue;
import com.serotonin.m2m2.rt.dataImage.types.MultistateValue;
import com.serotonin.m2m2.rt.dataImage.types.NumericValue;
import com.serotonin.m2m2.rt.maint.work.WorkItem;
import com.serotonin.m2m2.vo.pair.LongPair;
import com.serotonin.monitor.IntegerMonitor;
import com.serotonin.util.CollectionUtils;
import com.serotonin.util.queue.ObjectQueue;

public class PointValueDaoSQL extends BaseDao implements PointValueDao {
    private static List<UnsavedPointValue> UNSAVED_POINT_VALUES = new ArrayList<UnsavedPointValue>();

    private static final String POINT_VALUE_INSERT_START = "insert into pointValues (dataPointId, dataType, pointValue, ts) values ";
    private static final String POINT_VALUE_INSERT_VALUES = "(?,?,?,?)";
    private static final int POINT_VALUE_INSERT_VALUES_COUNT = 4;
    private static final String POINT_VALUE_INSERT = POINT_VALUE_INSERT_START + POINT_VALUE_INSERT_VALUES;
    private static final String POINT_VALUE_ANNOTATION_INSERT = "insert into pointValueAnnotations "
            + "(pointValueId, textPointValueShort, textPointValueLong, sourceMessage) values (?,?,?,?)";

    /**
     * Only the PointValueCache should call this method during runtime. Do not use.
     */
    @Override
    public PointValueTime savePointValueSync(int pointId, PointValueTime pointValue, SetPointSource source) {
        long id = savePointValueImpl(pointId, pointValue, source, false);

        PointValueTime savedPointValue;
        int retries = 5;
        while (true) {
            try {
                savedPointValue = getPointValue(id);
                break;
            }
            catch (ConcurrencyFailureException e) {
                if (retries <= 0)
                    throw e;
                retries--;
            }
        }

        return savedPointValue;
    }

    /**
     * Only the PointValueCache should call this method during runtime. Do not use.
     */
    @Override
    public void savePointValueAsync(int pointId, PointValueTime pointValue, SetPointSource source) {
        savePointValueImpl(pointId, pointValue, source, true);
    }

    long savePointValueImpl(final int pointId, final PointValueTime pointValue, final SetPointSource source,
            boolean async) {
        DataValue value = pointValue.getValue();
        final int dataType = DataTypes.getDataType(value);
        double dvalue = 0;
        String svalue = null;

        if (dataType == DataTypes.IMAGE) {
            ImageValue imageValue = (ImageValue) value;
            dvalue = imageValue.getType();
            if (imageValue.isSaved())
                svalue = Long.toString(imageValue.getId());
        }
        else if (value.hasDoubleRepresentation())
            dvalue = value.getDoubleValue();
        else
            svalue = value.getStringValue();

        // Check if we need to create an annotation.
        long id;
        try {
            if (svalue != null || source != null || dataType == DataTypes.IMAGE)
                async = false;
            id = savePointValue(pointId, dataType, dvalue, pointValue.getTime(), svalue, source, async);
        }
        catch (ConcurrencyFailureException e) {
            // Still failed to insert after all of the retries. Store the data
            synchronized (UNSAVED_POINT_VALUES) {
                UNSAVED_POINT_VALUES.add(new UnsavedPointValue(pointId, pointValue, source));
            }
            return -1;
        }

        // Check if we need to save an image
        if (dataType == DataTypes.IMAGE) {
            ImageValue imageValue = (ImageValue) value;
            if (!imageValue.isSaved()) {
                imageValue.setId(id);

                File file = new File(Common.getFiledataPath(), imageValue.getFilename());

                // Write the file.
                FileOutputStream out = null;
                try {
                    out = new FileOutputStream(file);
                    StreamUtils.transfer(new ByteArrayInputStream(imageValue.getData()), out);
                }
                catch (IOException e) {
                    // Rethrow as an RTE
                    throw new ImageSaveException(e);
                }
                finally {
                    try {
                        if (out != null)
                            out.close();
                    }
                    catch (IOException e) {
                        // no op
                    }
                }

                // Allow the data to be GC'ed
                imageValue.setData(null);
            }
        }

        clearUnsavedPointValues();
        clearUnsavedPointUpdates();

        return id;
    }

    private void clearUnsavedPointValues() {
        if (!UNSAVED_POINT_VALUES.isEmpty()) {
            synchronized (UNSAVED_POINT_VALUES) {
                while (!UNSAVED_POINT_VALUES.isEmpty()) {
                    UnsavedPointValue data = UNSAVED_POINT_VALUES.remove(0);
                    savePointValueImpl(data.getPointId(), data.getPointValue(), data.getSource(), false);
                }
            }
        }
    }

    long savePointValue(final int pointId, final int dataType, double dvalue, final long time, final String svalue,
            final SetPointSource source, boolean async) {
        // Apply database specific bounds on double values.
        dvalue = Common.databaseProxy.applyBounds(dvalue);

        if (async) {
            BatchWriteBehind.add(new BatchWriteBehindEntry(pointId, dataType, dvalue, time), ejt);
            return -1;
        }

        int retries = 5;
        while (true) {
            try {
                return savePointValueImpl(pointId, dataType, dvalue, time, svalue, source);
            }
            catch (ConcurrencyFailureException e) {
                if (retries <= 0)
                    throw e;
                retries--;
            }
            catch (RuntimeException e) {
                throw new RuntimeException("Error saving point value: dataType=" + dataType + ", dvalue=" + dvalue, e);
            }
        }
    }

    private long savePointValueImpl(int pointId, int dataType, double dvalue, long time, String svalue,
            SetPointSource source) {
        long id = doInsertLong(POINT_VALUE_INSERT, new Object[] { pointId, dataType, dvalue, time });

        if (svalue == null && dataType == DataTypes.IMAGE)
            svalue = Long.toString(id);

        // Check if we need to create an annotation.
        TranslatableMessage sourceMessage = null;
        if (source != null)
            sourceMessage = source.getSetPointSourceMessage();

        if (svalue != null || sourceMessage != null) {
            String shortString = null;
            String longString = null;
            if (svalue != null) {
                if (svalue.length() > 128)
                    longString = svalue;
                else
                    shortString = svalue;
            }

            ejt.update(POINT_VALUE_ANNOTATION_INSERT, //
                    new Object[] { id, shortString, longString, writeTranslatableMessage(sourceMessage) }, //
                    new int[] { Types.INTEGER, Types.VARCHAR, Types.CLOB, Types.CLOB });
        }

        return id;
    }

    //Update Point Values
    private static List<UnsavedPointUpdate> UNSAVED_POINT_UPDATES = new ArrayList<UnsavedPointUpdate>();

    private static final String POINT_VALUE_UPDATE = "UPDATE pointValues SET dataType=?, pointValue=? ";
    private static final String POINT_VALUE_ANNOTATION_UPDATE = "UPDATE pointValueAnnotations SET"
            + "textPointValueShort=?, textPointValueLong=?, sourceMessage=?  ";

    /**
     * Only the PointValueCache should call this method during runtime. Do not use.
     */
    @Override
    public PointValueTime updatePointValueSync(int dataPointId, PointValueTime pvt, SetPointSource source) {
        long id = updatePointValueImpl(dataPointId, pvt, source, false);

        PointValueTime savedPointValue;
        int retries = 5;
        while (true) {
            try {
                savedPointValue = getPointValue(id);
                break;
            }
            catch (ConcurrencyFailureException e) {
                if (retries <= 0)
                    throw e;
                retries--;
            }
        }

        return savedPointValue;
    }

    /**
     * Only the PointValueCache should call this method during runtime. Do not use.
     */
    @Override
    public void updatePointValueAsync(int pointId, PointValueTime pointValue, SetPointSource source) {
        updatePointValueImpl(pointId, pointValue, source, true);
    }

    long updatePointValueImpl(final int pointId, final PointValueTime pvt, final SetPointSource source, boolean async) {
        DataValue value = pvt.getValue();
        final int dataType = DataTypes.getDataType(value);
        double dvalue = 0;
        String svalue = null;

        if (dataType == DataTypes.IMAGE) {
            ImageValue imageValue = (ImageValue) value;
            dvalue = imageValue.getType();
            if (imageValue.isSaved())
                svalue = Long.toString(imageValue.getId());
        }
        else if (value.hasDoubleRepresentation())
            dvalue = value.getDoubleValue();
        else
            svalue = value.getStringValue();

        // Check if we need to create an annotation.
        long id;
        try {
            if (svalue != null || source != null || dataType == DataTypes.IMAGE)
                async = false;
            id = updatePointValue(pointId, dataType, dvalue, pvt.getTime(), svalue, source, async);
        }
        catch (ConcurrencyFailureException e) {
            // Still failed to insert after all of the retries. Store the data
            synchronized (UNSAVED_POINT_UPDATES) {
                UNSAVED_POINT_UPDATES.add(new UnsavedPointUpdate(pointId, pvt, source));
            }
            return -1;
        }

        // Check if we need to save an image
        if (dataType == DataTypes.IMAGE) {
            ImageValue imageValue = (ImageValue) value;
            if (!imageValue.isSaved()) {
                imageValue.setId(id);

                File file = new File(Common.getFiledataPath(), imageValue.getFilename());

                // Write the file.
                FileOutputStream out = null;
                try {
                    out = new FileOutputStream(file);
                    StreamUtils.transfer(new ByteArrayInputStream(imageValue.getData()), out);
                }
                catch (IOException e) {
                    // Rethrow as an RTE
                    throw new ImageSaveException(e);
                }
                finally {
                    try {
                        if (out != null)
                            out.close();
                    }
                    catch (IOException e) {
                        // no op
                    }
                }

                // Allow the data to be GC'ed
                imageValue.setData(null);
            }
        }

        clearUnsavedPointUpdates();

        return id;
    }

    private void clearUnsavedPointUpdates() {
        if (!UNSAVED_POINT_UPDATES.isEmpty()) {
            synchronized (UNSAVED_POINT_UPDATES) {
                while (!UNSAVED_POINT_UPDATES.isEmpty()) {
                    UnsavedPointUpdate data = UNSAVED_POINT_UPDATES.remove(0);
                    updatePointValueImpl(data.getPointId(), data.getPointValue(), data.getSource(), false);
                }
            }
        }
    }

    long updatePointValue(final int dataPointId, final int dataType, double dvalue, final long time,
            final String svalue, final SetPointSource source, boolean async) {
        // Apply database specific bounds on double values.
        dvalue = Common.databaseProxy.applyBounds(dvalue);

        if (async) {
            BatchUpdateBehind.add(new BatchUpdateBehindEntry(dataPointId, dataType, dvalue, time, svalue, source), ejt);
            return -1;
        }

        int retries = 5;
        while (true) {
            try {
                return updatePointValueImpl(dataPointId, dataType, dvalue, time, svalue, source);
            }
            catch (ConcurrencyFailureException e) {
                if (retries <= 0)
                    throw e;
                retries--;
            }
            catch (RuntimeException e) {
                throw new RuntimeException("Error saving point value: dataType=" + dataType + ", dvalue=" + dvalue, e);
            }
        }
    }

    private long updatePointValueImpl(int dataPointId, int dataType, double dvalue, long time, String svalue,
            SetPointSource source) {
        long id = doInsertLong(POINT_VALUE_UPDATE + " WHERE ts = ? AND dataPointId = ?", new Object[] { dataType,
                dvalue, time, dataPointId });

        this.updatePointValueAnnotation(id, dataType, svalue, source);

        return id;
    }

    private void updatePointValueAnnotation(long id, int dataType, String svalue, SetPointSource source) {

        if (svalue == null && dataType == DataTypes.IMAGE)
            svalue = Long.toString(id);

        // Check if we need to create an annotation.
        TranslatableMessage sourceMessage = null;
        if (source != null)
            sourceMessage = source.getSetPointSourceMessage();

        if (svalue != null || sourceMessage != null) {
            String shortString = null;
            String longString = null;
            if (svalue != null) {
                if (svalue.length() > 128)
                    longString = svalue;
                else
                    shortString = svalue;
            }

            ejt.update(POINT_VALUE_ANNOTATION_UPDATE + "WHERE pointValueId = ?", //
                    new Object[] { shortString, longString, writeTranslatableMessage(sourceMessage), id }, //
                    new int[] { Types.VARCHAR, Types.CLOB, Types.CLOB, Types.INTEGER });
        }

    }

    //
    //
    // Queries
    //
    private static final String POINT_VALUE_SELECT = //
    "select pv.dataType, pv.pointValue, pva.textPointValueShort, pva.textPointValueLong, pv.ts, pva.sourceMessage " //
            + "from pointValues pv " //
            + "  left join pointValueAnnotations pva on pv.id = pva.pointValueId";

    //
    //
    // Single point
    //
    @Override
    public PointValueTime getLatestPointValue(int dataPointId) {

        long maxTs = ejt.queryForLong("select max(ts) from pointValues where dataPointId=?",
                new Object[] { dataPointId }, 0);
        if (maxTs == 0)
            return null;
        return pointValueQuery(POINT_VALUE_SELECT + " where pv.dataPointId=? and pv.ts=?", new Object[] { dataPointId,
                maxTs });
    }

    private PointValueTime getPointValue(long id) {
        return pointValueQuery(POINT_VALUE_SELECT + " where pv.id=?", new Object[] { id });
    }

    @Override
    public PointValueTime getPointValueBefore(int dataPointId, long time) {
        Long valueTime = queryForObject("select max(ts) from pointValues where dataPointId=? and ts<?", new Object[] {
                dataPointId, time }, Long.class, null);
        if (valueTime == null)
            return null;
        return getPointValueAt(dataPointId, valueTime);
    }

    @Override
    public PointValueTime getPointValueAt(int dataPointId, long time) {
        return pointValueQuery(POINT_VALUE_SELECT + " where pv.dataPointId=? and pv.ts=?", new Object[] { dataPointId,
                time });
    }

    @Override
    public PointValueTime getPointValueAfter(int dataPointId, long time) {
        Long valueTime = queryForObject("select min(ts) from pointValues where dataPointId=? and ts>=?", new Object[] {
                dataPointId, time }, Long.class, null);
        if (valueTime == null)
            return null;
        return getPointValueAt(dataPointId, valueTime);
    }

    private PointValueTime pointValueQuery(String sql, Object[] params) {
        List<PointValueTime> result = pointValuesQuery(sql, params, 1);
        if (result.size() == 0)
            return null;
        return result.get(0);
    }

    //
    //
    // Values lists
    //
    @Override
    public List<PointValueTime> getPointValues(int dataPointId, long since) {
        return pointValuesQuery(POINT_VALUE_SELECT + " where pv.dataPointId=? and pv.ts >= ? order by ts",
                new Object[] { dataPointId, since }, 0);
    }

    @Override
    public List<PointValueTime> getPointValuesBetween(int dataPointId, long from, long to) {
        return pointValuesQuery(POINT_VALUE_SELECT + " where pv.dataPointId=? and pv.ts >= ? and pv.ts<? order by ts",
                new Object[] { dataPointId, from, to }, 0);
    }

    @Override
    public List<PointValueTime> getLatestPointValues(int dataPointId, int limit) {
        if (limit == 0)
            return Collections.emptyList();
        if (limit == 1)
            return CollectionUtils.toList(getLatestPointValue(dataPointId));
        return pointValuesQuery(POINT_VALUE_SELECT + " where pv.dataPointId=? order by pv.ts desc",
                new Object[] { dataPointId }, limit);
    }

    @Override
    public List<PointValueTime> getLatestPointValues(int dataPointId, int limit, long before) {
        return pointValuesQuery(POINT_VALUE_SELECT + " where pv.dataPointId=? and pv.ts<? order by pv.ts desc",
                new Object[] { dataPointId, before }, limit);
    }

    private List<PointValueTime> pointValuesQuery(String sql, Object[] params, int limit) {
        return Common.databaseProxy.doLimitQuery(this, sql, params, new PointValueRowMapper(), limit);
    }

    //
    //
    // Query with callback
    //
    @Override
    public void getPointValuesBetween(int dataPointId, long from, long to, MappedRowCallback<PointValueTime> callback) {
        query(POINT_VALUE_SELECT + " where pv.dataPointId=? and pv.ts >= ? and pv.ts<? order by ts", new Object[] {
                dataPointId, from, to }, new PointValueRowMapper(), callback);
    }

    class PointValueRowMapper implements RowMapper<PointValueTime> {
        @Override
        public PointValueTime mapRow(ResultSet rs, int rowNum) throws SQLException {
            DataValue value = createDataValue(rs, 1);
            long time = rs.getLong(5);

            TranslatableMessage sourceMessage = BaseDao.readTranslatableMessage(rs, 6);
            if (sourceMessage == null)
                // No annotations, just return a point value.
                return new PointValueTime(value, time);

            // There was a source for the point value, so return an annotated version.
            return new AnnotatedPointValueTime(value, time, sourceMessage);
        }
    }

    /*
     * Queries for Point Values with Ids
     */
    //    private static final String POINT_VALUE_ID_SELECT = //
    //    "select pv.id, pv.dataType, pv.pointValue, pva.textPointValueShort, pva.textPointValueLong, pv.ts, pva.sourceMessage " //
    //            + "from pointValues pv " //
    //            + "  left join pointValueAnnotations pva on pv.id = pva.pointValueId";
    //    
    //    public PointValueIdTime getPointValueIdAt(int dataPointId, long time) {
    //        return pointValueIdQuery(POINT_VALUE_ID_SELECT + " where pv.dataPointId=? and pv.ts=?", new Object[] { dataPointId,
    //                time });
    //    }
    //    private PointValueIdTime getPointValueId(long id) {
    //        return pointValueIdQuery(POINT_VALUE_ID_SELECT + " where pv.id=?", new Object[] { id });
    //    }
    //    
    //    public List<PointValueIdTime> getPointValuesWithIdsBetween(int dataPointId, long from, long to) {
    //        return pointValuesIdsQuery(POINT_VALUE_ID_SELECT + " where pv.dataPointId=? and pv.ts >= ? and pv.ts<? order by ts",
    //                new Object[] { dataPointId, from, to }, 0);
    //    }
    //    public void getPointValuesWithIdsBetween(int dataPointId, long from, long to, MappedRowCallback<PointValueIdTime> callback) {
    //        query(POINT_VALUE_ID_SELECT + " where pv.dataPointId=? and pv.ts >= ? and pv.ts<? order by ts", new Object[] {
    //                dataPointId, from, to }, new PointValueIdRowMapper(), callback);
    //    }
    //
    //    private List<PointValueIdTime> pointValuesIdsQuery(String sql, Object[] params, int limit) {
    //        return Common.databaseProxy.doLimitQuery(this, sql, params, new PointValueIdRowMapper(), limit);
    //    }
    //    
    //    private List<PointValueIdTime> pointValueIdsQuery(String sql, Object[] params, int limit) {
    //        return Common.databaseProxy.doLimitQuery(this, sql, params, new PointValueIdRowMapper(), limit);
    //    }
    //
    //    private PointValueIdTime pointValueIdQuery(String sql, Object[] params) {
    //        List<PointValueIdTime> result = pointValueIdsQuery(sql, params, 1);
    //        if (result.size() == 0)
    //            return null;
    //        return result.get(0);
    //    }
    //    
    //    class PointValueIdRowMapper implements RowMapper<PointValueIdTime> {
    //        @Override
    //        public PointValueIdTime mapRow(ResultSet rs, int rowNum) throws SQLException {
    //            int id = rs.getInt(1);
    //        	DataValue value = createDataValue(rs, 2);
    //        	
    //            long time = rs.getLong(6);
    //
    //            TranslatableMessage sourceMessage = BaseDao.readTranslatableMessage(rs, 7);
    //            if (sourceMessage == null)
    //                // No annotations, just return a point value.
    //                return new PointValueIdTime(id,value, time);
    //
    //            // There was a source for the point value, so return an annotated version.
    //            return new AnnotatedPointValueIdTime(id,value, time, sourceMessage);
    //        }
    //    }

    DataValue createDataValue(ResultSet rs, int firstParameter) throws SQLException {
        int dataType = rs.getInt(firstParameter);
        DataValue value;
        switch (dataType) {
        case (DataTypes.NUMERIC):
            value = new NumericValue(rs.getDouble(firstParameter + 1));
            break;
        case (DataTypes.BINARY):
            value = new BinaryValue(rs.getDouble(firstParameter + 1) == 1);
            break;
        case (DataTypes.MULTISTATE):
            value = new MultistateValue(rs.getInt(firstParameter + 1));
            break;
        case (DataTypes.ALPHANUMERIC):
            String s = rs.getString(firstParameter + 2);
            if (s == null)
                s = rs.getString(firstParameter + 3);
            value = new AlphanumericValue(s);
            break;
        case (DataTypes.IMAGE):
            value = new ImageValue(Integer.parseInt(rs.getString(firstParameter + 2)), rs.getInt(firstParameter + 1));
            break;
        default:
            value = null;
        }
        return value;
    }

    //
    //
    // Multiple-point callback for point history replays
    //
    private static final String POINT_ID_VALUE_SELECT = "select pv.dataPointId, pv.dataType, pv.pointValue, " //
            + "pva.textPointValueShort, pva.textPointValueLong, pv.ts "
            + "from pointValues pv "
            + "  left join pointValueAnnotations pva on pv.id = pva.pointValueId";

    @Override
    public void getPointValuesBetween(List<Integer> dataPointIds, long from, long to,
            MappedRowCallback<IdPointValueTime> callback) {
        String ids = createDelimitedList(dataPointIds, ",", null);
        query(POINT_ID_VALUE_SELECT + " where pv.dataPointId in (" + ids + ") and pv.ts >= ? and pv.ts<? order by ts",
                new Object[] { from, to }, new IdPointValueRowMapper(), callback);
    }

    /**
     * Note: this does not extract source information from the annotation.
     */
    class IdPointValueRowMapper implements RowMapper<IdPointValueTime> {
        @Override
        public IdPointValueTime mapRow(ResultSet rs, int rowNum) throws SQLException {
            int dataPointId = rs.getInt(1);
            DataValue value = createDataValue(rs, 2);
            long time = rs.getLong(6);
            return new IdPointValueTime(dataPointId, value, time);
        }
    }

    //
    //
    // Point value deletions
    //

    public long deletePointValue(int pointValueId) {
        return deletePointValues("delete from pointValues where id = ?", new Object[] { pointValueId }, 0, 0);
    }

    @Override
    public long deletePointValue(int dataPointId, long ts) {
        return deletePointValues("delete from pointValues where dataPointId = ? AND ts = ?", new Object[] {
                dataPointId, ts }, 0, 0);
    }

    @Override
    public long deletePointValuesBefore(int dataPointId, long time) {
        return deletePointValues("delete from pointValues where dataPointId=? and ts<?", new Object[] { dataPointId,
                time }, 0, 0);
    }

    @Override
    public long deletePointValues(int dataPointId) {
        return deletePointValues("delete from pointValues where dataPointId=?", new Object[] { dataPointId }, 0, 0);
    }

    @Override
    public long deleteAllPointData() {
        return deletePointValues("delete from pointValues", null, 0, 0);
    }

    @Override
    public long deletePointValuesWithMismatchedType(int dataPointId, int dataType) {
        return deletePointValues("delete from pointValues where dataPointId=? and dataType<>?", new Object[] {
                dataPointId, dataType }, 0, 0);
    }

    @Override
    public long deleteOrphanedPointValues() {
        return deletePointValues("DELETE FROM pointValues WHERE dataPointId NOT IN (SELECT ID FROM dataPoints)", null,
                5000, 100000);
    }

    @Override
    public void deleteOrphanedPointValueAnnotations() {
        RowMapper<Long> rm = new RowMapper<Long>() {
            @Override
            public Long mapRow(ResultSet rs, int row) throws SQLException {
                return rs.getLong(1);
            }
        };
        int limit = 1000;
        while (true) {
            List<Long> ids = Common.databaseProxy.doLimitQuery(this,
                    "select pointValueId from pointValueAnnotations pa "
                            + "left join pointValues p on pa.pointValueId=p.id where p.id is null", null, rm, limit);

            if (ids.isEmpty())
                break;

            String idStr = createDelimitedList(ids, ",", null);
            ejt.update("delete from pointValueAnnotations where pointValueId in (" + idStr + ")");
            if (ids.size() < limit)
                break;
        }
    }

    private long deletePointValues(String sql, Object[] params, int chunkWait, int limit) {
        long cnt = Common.databaseProxy.doLimitDelete(ejt, sql, params, 1000, chunkWait, limit);
        clearUnsavedPointValues();
        return cnt;
    }

    /**
     * There WAS a bug here where the end date should be exclusive! The TCP Persistent publisher expects it to be
     * exclusive,
     * but as for what ramifications it will have to other modules who knows.
     * 
     * For example if one uses this method to count a range and then a select point values between, the results can be
     * different!
     * 
     * This has been changed to be exclusive of End time as the NoSQL DB uses exclusive queries and this needs to 
     * match for the Persistent TCP Module to work across various Data stores.
     * 
     */
    @Override
    public long dateRangeCount(int dataPointId, long from, long to) {
        return ejt.queryForLong("select count(*) from pointValues where dataPointId=? and ts>=? and ts<?",
                new Object[] { dataPointId, from, to }, 0l);
    }

    @Override
    public long getInceptionDate(int dataPointId) {
        return ejt
                .queryForLong("select min(ts) from pointValues where dataPointId=?", new Object[] { dataPointId }, -1);
    }

    @Override
    public long getStartTime(List<Integer> dataPointIds) {
        if (dataPointIds.isEmpty())
            return -1;
        return ejt.queryForLong("select min(ts) from pointValues where dataPointId in ("
                + createDelimitedList(dataPointIds, ",", null) + ")", null, 0l);
    }

    @Override
    public long getEndTime(List<Integer> dataPointIds) {
        if (dataPointIds.isEmpty())
            return -1;
        return ejt.queryForLong("select max(ts) from pointValues where dataPointId in ("
                + createDelimitedList(dataPointIds, ",", null) + ")", null, -1l);
    }

    @Override
    public LongPair getStartAndEndTime(List<Integer> dataPointIds) {
        if (dataPointIds.isEmpty())
            return null;
        return queryForObject(
                "select min(ts),max(ts) from pointValues where dataPointId in ("
                        + createDelimitedList(dataPointIds, ",", null) + ")", null, new RowMapper<LongPair>() {
                    @Override
                    public LongPair mapRow(ResultSet rs, int index) throws SQLException {
                        long l = rs.getLong(1);
                        if (rs.wasNull())
                            return null;
                        return new LongPair(l, rs.getLong(2));
                    }
                }, null);
    }

    @Override
    public List<Long> getFiledataIds(int dataPointId) {
        return queryForList("select id from pointValues where dataPointId=? and dataType=? ", new Object[] {
                dataPointId, DataTypes.IMAGE }, Long.class);
    }

    /**
     * Class that stored point value data when it could not be saved to the database due to concurrency errors.
     * 
     * @author Matthew Lohbihler
     */
    class UnsavedPointValue {
        private final int pointId;
        private final PointValueTime pointValue;
        private final SetPointSource source;

        public UnsavedPointValue(int pointId, PointValueTime pointValue, SetPointSource source) {
            this.pointId = pointId;
            this.pointValue = pointValue;
            this.source = source;
        }

        public int getPointId() {
            return pointId;
        }

        public PointValueTime getPointValue() {
            return pointValue;
        }

        public SetPointSource getSource() {
            return source;
        }
    }

    /**
     * Class that stored point value data when it could not be saved to the database due to concurrency errors.
     * 
     * @author Matthew Lohbihler
     */
    class UnsavedPointUpdate {
        private final int pointId;
        private final PointValueTime pointValue;
        private final SetPointSource source;

        public UnsavedPointUpdate(int pointId, PointValueTime pointValue, SetPointSource source) {
            this.pointId = pointId;
            this.pointValue = pointValue;
            this.source = source;
        }

        public int getPointId() {
            return pointId;
        }

        public PointValueTime getPointValue() {
            return pointValue;
        }

        public SetPointSource getSource() {
            return source;
        }
    }

    class BatchWriteBehindEntry {
        private final int pointId;
        private final int dataType;
        private final double dvalue;
        private final long time;

        public BatchWriteBehindEntry(int pointId, int dataType, double dvalue, long time) {
            this.pointId = pointId;
            this.dataType = dataType;
            this.dvalue = dvalue;
            this.time = time;
        }

        public void writeInto(Object[] params, int index) {
            index *= POINT_VALUE_INSERT_VALUES_COUNT;
            params[index++] = pointId;
            params[index++] = dataType;
            params[index++] = dvalue;
            params[index++] = time;
        }
    }

    public static final String ENTRIES_MONITOR_ID = "com.serotonin.m2m2.db.dao.PointValueDao$BatchWriteBehind.ENTRIES_MONITOR";
    public static final String INSTANCES_MONITOR_ID = "com.serotonin.m2m2.db.dao.PointValueDao$BatchWriteBehind.INSTANCES_MONITOR";
    public static final String BATCH_WRITE_SPEED_MONITOR_ID = "com.serotonin.m2m2.db.dao.PointValueDao$BatchWriteBehind.BATCH_WRITE_SPEED_MONITOR";

    static class BatchWriteBehind implements WorkItem {
        private static final ObjectQueue<BatchWriteBehindEntry> ENTRIES = new ObjectQueue<PointValueDaoSQL.BatchWriteBehindEntry>();
        private static final CopyOnWriteArrayList<BatchWriteBehind> instances = new CopyOnWriteArrayList<BatchWriteBehind>();
        private static Log LOG = LogFactory.getLog(BatchWriteBehind.class);
        private static final int SPAWN_THRESHOLD = 10000;
        private static final int MAX_INSTANCES = 5;
        private static int MAX_ROWS = 1000;
        private static final IntegerMonitor ENTRIES_MONITOR = new IntegerMonitor(ENTRIES_MONITOR_ID,
                "internal.monitor.BATCH_ENTRIES");
        private static final IntegerMonitor INSTANCES_MONITOR = new IntegerMonitor(INSTANCES_MONITOR_ID,
                "internal.monitor.BATCH_INSTANCES");
        //TODO Create DoubleMonitor but will need to upgrade the Internal data source to do this
        private static final IntegerMonitor BATCH_WRITE_SPEED_MONITOR = new IntegerMonitor(
                BATCH_WRITE_SPEED_MONITOR_ID, "internal.monitor.BATCH_WRITE_SPEED_MONITOR");

        private static List<Class<? extends RuntimeException>> retriedExceptions = new ArrayList<Class<? extends RuntimeException>>();

        static {
            if (Common.databaseProxy.getType() == DatabaseType.DERBY)
                // This has not been tested to be optimal
                MAX_ROWS = 1000;
            else if (Common.databaseProxy.getType() == DatabaseType.H2)
                // This has not been tested to be optimal
                MAX_ROWS = 1000;
            else if (Common.databaseProxy.getType() == DatabaseType.MSSQL)
                // MSSQL has max rows of 1000, and max parameters of 2100. In this case that works out to...
                MAX_ROWS = 524;
            else if (Common.databaseProxy.getType() == DatabaseType.MYSQL)
                // This appears to be an optimal value
                MAX_ROWS = 2000;
            else if (Common.databaseProxy.getType() == DatabaseType.POSTGRES)
                // This appears to be an optimal value
                MAX_ROWS = 2000;
            else
                throw new ShouldNeverHappenException("Unknown database type: " + Common.databaseProxy.getType());

            Common.MONITORED_VALUES.addIfMissingStatMonitor(ENTRIES_MONITOR);
            Common.MONITORED_VALUES.addIfMissingStatMonitor(INSTANCES_MONITOR);
            Common.MONITORED_VALUES.addIfMissingStatMonitor(BATCH_WRITE_SPEED_MONITOR);

            retriedExceptions.add(RecoverableDataAccessException.class);
            retriedExceptions.add(TransientDataAccessException.class);
            retriedExceptions.add(TransientDataAccessResourceException.class);
            retriedExceptions.add(CannotGetJdbcConnectionException.class);
        }

        static void add(BatchWriteBehindEntry e, ExtendedJdbcTemplate ejt) {
            synchronized (ENTRIES) {
                ENTRIES.push(e);
                ENTRIES_MONITOR.setValue(ENTRIES.size());
                if (ENTRIES.size() > instances.size() * SPAWN_THRESHOLD) {
                    if (instances.size() < MAX_INSTANCES) {
                        BatchWriteBehind bwb = new BatchWriteBehind(ejt);
                        instances.add(bwb);
                        INSTANCES_MONITOR.setValue(instances.size());
                        try {
                            Common.backgroundProcessing.addWorkItem(bwb);
                        }
                        catch (RejectedExecutionException ree) {
                            instances.remove(bwb);
                            INSTANCES_MONITOR.setValue(instances.size());
                            throw ree;
                        }
                    }
                }
            }
        }

        private final ExtendedJdbcTemplate ejt;

        public BatchWriteBehind(ExtendedJdbcTemplate ejt) {
            this.ejt = ejt;
        }

        @Override
        public void execute() {
            try {
                BatchWriteBehindEntry[] inserts;
                while (true) {
                    synchronized (ENTRIES) {
                        if (ENTRIES.size() == 0)
                            break;

                        inserts = new BatchWriteBehindEntry[ENTRIES.size() < MAX_ROWS ? ENTRIES.size() : MAX_ROWS];
                        ENTRIES.pop(inserts);
                        ENTRIES_MONITOR.setValue(ENTRIES.size());
                    }

                    // Create the sql and parameters
                    Object[] params = new Object[inserts.length * POINT_VALUE_INSERT_VALUES_COUNT];
                    StringBuilder sb = new StringBuilder();
                    sb.append(POINT_VALUE_INSERT_START);
                    for (int i = 0; i < inserts.length; i++) {
                        if (i > 0)
                            sb.append(',');
                        sb.append(POINT_VALUE_INSERT_VALUES);
                        inserts[i].writeInto(params, i);
                    }

                    // Insert the data
                    int retries = 10;
                    while (true) {
                        try {

                            Long time = null;
                            if (inserts.length > 10) {
                            	time = System.currentTimeMillis();
                            }

                            ejt.update(sb.toString(), params);

                            if (time != null) {
                                long elapsed = System.currentTimeMillis() - time;
                                if (elapsed > 0) {
                                    double writesPerSecond = ((double) inserts.length / (double) elapsed) * 1000d;
                                    BATCH_WRITE_SPEED_MONITOR.setValue((int) writesPerSecond);
                                }
                            }

                            break;
                        }
                        catch (RuntimeException e) {
                            if (retriedExceptions.contains(e.getClass())) {
                                if (retries <= 0) {
                                    LOG.error("Concurrency failure saving " + inserts.length
                                            + " batch inserts after 10 tries. Data lost.");
                                    break;
                                }

                                int wait = (10 - retries) * 100;
                                try {
                                    if (wait > 0) {
                                        synchronized (this) {
                                            wait(wait);
                                        }
                                    }
                                }
                                catch (InterruptedException ie) {
                                    // no op
                                }

                                retries--;
                            }
                            else {
                                LOG.error("Error saving " + inserts.length + " batch inserts. Data lost.", e);
                                break;
                            }
                        }
                    }
                }
            }
            finally {
                instances.remove(this);
                INSTANCES_MONITOR.setValue(instances.size());
            }
        }

        @Override
        public int getPriority() {
            return WorkItem.PRIORITY_HIGH;
        }

		/* (non-Javadoc)
		 * @see com.serotonin.m2m2.rt.maint.work.WorkItem#getDescription()
		 */
		@Override
		public String getDescription() {
			return "Batch Writing from batch of size: " + ENTRIES.size(); 
		}
    }

    //
    //Batch Updating
    //
    //
    class BatchUpdateBehindEntry {
        private final int dataPointId;
        private final int dataType;
        private final double dvalue;
        private final long time;
        private final String svalue;
        private final SetPointSource source;

        public BatchUpdateBehindEntry(int dataPointId, int dataType, double dvalue, long time, String svalue,
                SetPointSource source) {
            this.dataPointId = dataPointId;
            this.dataType = dataType;
            this.dvalue = dvalue;
            this.time = time;
            this.svalue = svalue;
            this.source = source;
        }

        public void writeInto(Object[] params, int index) {

            params[index++] = dataType;
            params[index++] = dvalue;
            params[index++] = time;
            params[index++] = dataPointId;
            params[index++] = svalue;
            params[index++] = source;
        }
    }

    public static final String ENTRIES_UPDATE_MONITOR_ID = BatchUpdateBehind.class.getName() + ".ENTRIES_MONITOR";
    public static final String INSTANCES_UPDATE_MONITOR_ID = BatchUpdateBehind.class.getName() + ".INSTANCES_MONITOR";

    static class BatchUpdateBehind implements WorkItem {
        private static final ObjectQueue<BatchUpdateBehindEntry> ENTRIES = new ObjectQueue<PointValueDaoSQL.BatchUpdateBehindEntry>();
        private static final CopyOnWriteArrayList<BatchUpdateBehind> instances = new CopyOnWriteArrayList<BatchUpdateBehind>();
        private static Log LOG = LogFactory.getLog(BatchUpdateBehind.class);
        private static final int SPAWN_THRESHOLD = 10000;
        private static final int MAX_INSTANCES = 5;
        private static int MAX_ROWS = 1000;
        private static final IntegerMonitor ENTRIES_MONITOR = new IntegerMonitor(ENTRIES_UPDATE_MONITOR_ID,
                "internal.monitor.BATCH_ENTRIES");
        private static final IntegerMonitor INSTANCES_MONITOR = new IntegerMonitor(INSTANCES_UPDATE_MONITOR_ID,
                "internal.monitor.BATCH_INSTANCES");

        private static List<Class<? extends RuntimeException>> retriedExceptions = new ArrayList<Class<? extends RuntimeException>>();

        static {
            if (Common.databaseProxy.getType() == DatabaseType.DERBY)
                // This has not been tested to be optimal
                MAX_ROWS = 1000;
            else if (Common.databaseProxy.getType() == DatabaseType.H2)
                // This has not been tested to be optimal
                MAX_ROWS = 1000;
            else if (Common.databaseProxy.getType() == DatabaseType.MSSQL)
                // MSSQL has max rows of 1000, and max parameters of 2100. In this case that works out to...
                MAX_ROWS = 524;
            else if (Common.databaseProxy.getType() == DatabaseType.MYSQL)
                // This appears to be an optimal value
                MAX_ROWS = 2000;
            else if (Common.databaseProxy.getType() == DatabaseType.POSTGRES)
                // This appears to be an optimal value
                MAX_ROWS = 2000;
            else
                throw new ShouldNeverHappenException("Unknown database type: " + Common.databaseProxy.getType());

            Common.MONITORED_VALUES.addIfMissingStatMonitor(ENTRIES_MONITOR);
            Common.MONITORED_VALUES.addIfMissingStatMonitor(INSTANCES_MONITOR);

            retriedExceptions.add(RecoverableDataAccessException.class);
            retriedExceptions.add(TransientDataAccessException.class);
            retriedExceptions.add(TransientDataAccessResourceException.class);
            retriedExceptions.add(CannotGetJdbcConnectionException.class);
        }

        static void add(BatchUpdateBehindEntry e, ExtendedJdbcTemplate ejt) {
            synchronized (ENTRIES) {
                ENTRIES.push(e);
                ENTRIES_MONITOR.setValue(ENTRIES.size());
                if (ENTRIES.size() > instances.size() * SPAWN_THRESHOLD) {
                    if (instances.size() < MAX_INSTANCES) {
                        BatchUpdateBehind bwb = new BatchUpdateBehind(ejt);
                        instances.add(bwb);
                        INSTANCES_MONITOR.setValue(instances.size());
                        try {
                            Common.backgroundProcessing.addWorkItem(bwb);
                        }
                        catch (RejectedExecutionException ree) {
                            instances.remove(bwb);
                            INSTANCES_MONITOR.setValue(instances.size());
                            throw ree;
                        }
                    }
                }
            }
        }

        private final ExtendedJdbcTemplate ejt;

        public BatchUpdateBehind(ExtendedJdbcTemplate ejt) {
            this.ejt = ejt;
        }

        @Override
        public void execute() {
            try {
                BatchUpdateBehindEntry[] updates;
                while (true) {
                    synchronized (ENTRIES) {
                        if (ENTRIES.size() == 0)
                            break;

                        updates = new BatchUpdateBehindEntry[ENTRIES.size() < MAX_ROWS ? ENTRIES.size() : MAX_ROWS];
                        ENTRIES.pop(updates);
                        ENTRIES_MONITOR.setValue(ENTRIES.size());
                    }

                    // Create the sql and parameters
                    final int batchSize = updates.length;
                    final Object[][] params = new Object[updates.length][6];
                    for (int i = 0; i < updates.length; i++) {
                        updates[i].writeInto(params[i], 0);
                    }

                    // Insert the data
                    int retries = 10;
                    while (true) {
                        try {
                            int[] updatedIds = ejt.batchUpdate(
                                    POINT_VALUE_UPDATE + " WHERE ts = ? AND dataPointId = ?",
                                    new BatchPreparedStatementSetter() {
                                        @Override
                                        public int getBatchSize() {
                                            return batchSize;
                                        }

                                        @Override
                                        public void setValues(PreparedStatement ps, int i) throws SQLException {
                                            ps.setInt(1, (Integer) params[i][0]);
                                            ps.setDouble(2, (Double) params[i][1]);
                                            ps.setLong(3, (Long) params[i][2]);
                                            ps.setInt(4, (Integer) params[i][3]); //Update 
                                        }
                                    });

                            //Now if we have Annotation updates we need to apply those
                            if (updatedIds.length != params.length) {
                                LOG.fatal("Updated rows doesn't match necessary rows to update annotations!");
                            }
                            else {
                                PointValueDaoSQL dao = new PointValueDaoSQL();
                                for (int i = 0; i < updatedIds.length; i++) {

                                    if ((params[i][4] != null) || (params[i][5] != null)) {
                                        //Do the update
                                        dao.updatePointValueAnnotation(updatedIds[i], (Integer) params[i][0],
                                                (String) params[i][4], (SetPointSource) params[i][5]);
                                    }

                                }

                            }//end else we can do the update

                            break;
                        }
                        catch (RuntimeException e) {
                            if (retriedExceptions.contains(e.getClass())) {
                                if (retries <= 0) {
                                    LOG.error("Concurrency failure updating " + updates.length
                                            + " batch updates after 10 tries. Data lost.");
                                    break;
                                }

                                int wait = (10 - retries) * 100;
                                try {
                                    if (wait > 0) {
                                        synchronized (this) {
                                            wait(wait);
                                        }
                                    }
                                }
                                catch (InterruptedException ie) {
                                    // no op
                                }

                                retries--;
                            }
                            else {
                                LOG.error("Error saving " + updates.length + " batch updates. Data lost.", e);
                                break;
                            }
                        }
                    }
                }
            }
            finally {
                instances.remove(this);
                INSTANCES_MONITOR.setValue(instances.size());
            }
        }

        @Override
        public int getPriority() {
            return WorkItem.PRIORITY_HIGH;
        }

		/* (non-Javadoc)
		 * @see com.serotonin.m2m2.rt.maint.work.WorkItem#getDescription()
		 */
		@Override
		public String getDescription() {
			return "Batch Updating from batch of size: " + ENTRIES.size();
		}
    }

}
