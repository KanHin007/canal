package com.alibaba.otter.canal.client.adapter.rdb.service;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.common.base.Throwables;
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.alibaba.druid.pool.DruidDataSource;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONWriter.Feature;
import com.alibaba.otter.canal.client.adapter.rdb.config.MappingConfig;
import com.alibaba.otter.canal.client.adapter.rdb.config.MirrorDbConfig;
import com.alibaba.otter.canal.client.adapter.rdb.support.SingleDml;
import com.alibaba.otter.canal.client.adapter.rdb.support.SyncUtil;
import com.alibaba.otter.canal.client.adapter.support.Dml;

/**
 * RDB镜像库同步操作业务
 *
 * @author rewerma 2018-12-12 下午011:23
 * @version 1.0.0
 */
public class RdbMirrorDbSyncService {

    /**
     * DEFINER=`deploy`@`%`
     */
    private static final Pattern DDL_PATTERN = Pattern.compile("(create|CREATE)[ \n]+DEFINER=`[A-Za-z_0-9]+`@`[%.0-9A-Za-z]+`[ \n]+(function|FUNCTION|TRIGGER|trigger|PROCEDURE|procedure)?[ \n]+(`[0-9A-Za-z_]+`)");

    private static final Pattern CREATE_DATABASE_PATTERN = Pattern.compile("((create|CREATE)[ \n\r]+(DATABASE|database))|" +
                                                                            "((create|CREATE)[ \n\r]+(table|TABLE)[ \n\r]+[A-Za-z0-9_]+\\.`?[A-Za-z0-9_]`?)");

    private String jdbcUrl;

    private String jdbcDriver;

    private String jdbcUserName;

    private String jdbcPassword;

    private static final Logger logger = LoggerFactory.getLogger(RdbMirrorDbSyncService.class);

    private Map<String, MirrorDbConfig> mirrorDbConfigCache;                                           // 镜像库配置
    private DruidDataSource dataSource;
    private RdbSyncService rdbSyncService;                                                // rdbSyncService代理

    public RdbMirrorDbSyncService(Map<String, MirrorDbConfig> mirrorDbConfigCache, DruidDataSource dataSource,
                                  Integer threads, Map<String, Map<String, Integer>> columnsTypeCache,
                                  boolean skipDupException) {
        this.mirrorDbConfigCache = mirrorDbConfigCache;
        this.dataSource = dataSource;
        this.rdbSyncService = new RdbSyncService(dataSource, threads, columnsTypeCache, skipDupException);
    }

    public RdbMirrorDbSyncService(Map<String, MirrorDbConfig> mirrorDbConfigCache,
                                  DruidDataSource dataSource,
                                  Integer threads,
                                  Map<String, Map<String, Integer>> columnsTypeCache,
                                  String jdbcDriver,
                                  String jdbcUserName,
                                  String jdbcPassword,
                                  String jdbcUrl,
                                  boolean skipDupException) {
        this.mirrorDbConfigCache = mirrorDbConfigCache;
        this.dataSource = dataSource;
        this.rdbSyncService = new RdbSyncService(dataSource, threads, columnsTypeCache, skipDupException);
        this.jdbcUserName = jdbcUserName;
        this.jdbcDriver = jdbcDriver;
        this.jdbcPassword = jdbcPassword;
        // jdbc:mysql://172.16.20.36:10307/capital_enterprise_688739?useUnicode=true
        Pattern pattern = Pattern.compile("(jdbc:mysql://[A-Za-z0-9.:]+/)[A-Za-z0-9_]+");
        Matcher matcher = pattern.matcher(jdbcUrl);
        if(matcher.find()){
            this.jdbcUrl = matcher.group(1);
        }
        logger.info("jdbcUrl is {}",jdbcUrl);
    }

    /**
     * 批量同步方法
     *
     * @param dmls 批量 DML，包含DDL
     */
    public void sync(List<Dml> dmls) {
        List<Dml> dmlList = new ArrayList<>();
        for (Dml dml : dmls) {
            String destination = StringUtils.trimToEmpty(dml.getDestination());
            String database = dml.getDatabase();
            if (StringUtils.isBlank(database)) {
                continue;
            }
            database = "ent";
            MirrorDbConfig mirrorDbConfig = mirrorDbConfigCache.get(destination + "." + database);
            if (mirrorDbConfig == null) {
                continue;
            }
            if (mirrorDbConfig.getMappingConfig() == null) {
                continue;
            }
            if (dml.getGroupId() != null && StringUtils.isNotEmpty(mirrorDbConfig.getMappingConfig().getGroupId())) {
                if (!mirrorDbConfig.getMappingConfig().getGroupId().equals(dml.getGroupId())) {
                    continue; // 如果groupId不匹配则过滤
                }
            }

            if (dml.getIsDdl() != null && dml.getIsDdl() && StringUtils.isNotEmpty(dml.getSql())) {
                // 确保执行DDL前DML已执行完
                syncDml(dmlList);
                dmlList.clear();

                // DDL
                if (logger.isDebugEnabled()) {
                    logger.debug("DDL: {}", JSON.toJSONString(dml, Feature.WriteNulls));
                }
                executeDdl(mirrorDbConfig, dml);
                rdbSyncService.getColumnsTypeCache().remove(destination + "." + database + "." + dml.getTable());
                mirrorDbConfig.getTableConfig().remove(dml.getTable()); // 删除对应库表配置
            } else {
                // DML
                initMappingConfig(dml.getTable(), mirrorDbConfig.getMappingConfig(), mirrorDbConfig, dml);
                dmlList.add(dml);
            }
        }
        syncDml(dmlList);
    }

    /**
     * 批量同步Dml
     *
     * @param dmlList Dml列表，不包含DDL
     */
    private void syncDml(List<Dml> dmlList) {
        if (dmlList == null || dmlList.isEmpty()) {
            return;
        }
        rdbSyncService.sync(dmlList, dml -> {

            String database = dml.getDatabase();
            if (StringUtils.isBlank(database)) {
                return false;
            }

            database = "ent";

            MirrorDbConfig mirrorDbConfig = mirrorDbConfigCache.get(dml.getDestination() + "." + database);
            if (mirrorDbConfig == null) {
                return false;
            }
            String table = dml.getTable();
            MappingConfig config = mirrorDbConfig.getTableConfig().get(table);

            if (config == null) {
                return false;
            }
            rdbSyncService.appendDmlPartition(config, dml);
            return true;
        });
    }

    /**
     * 初始化表配置
     *
     * @param key           配置key: destination.database.table
     * @param baseConfigMap db sync config
     * @param dml           DML
     */
    private void initMappingConfig(String key, MappingConfig baseConfigMap, MirrorDbConfig mirrorDbConfig, Dml dml) {
        MappingConfig mappingConfig = mirrorDbConfig.getTableConfig().get(key);
        if (mappingConfig == null) {
            // 构造表配置
            mappingConfig = new MappingConfig();
            mappingConfig.setDataSourceKey(baseConfigMap.getDataSourceKey());
            mappingConfig.setDestination(baseConfigMap.getDestination());
            mappingConfig.setGroupId(baseConfigMap.getGroupId());
            mappingConfig.setOuterAdapterKey(baseConfigMap.getOuterAdapterKey());
            mappingConfig.setConcurrent(baseConfigMap.getConcurrent());
            MappingConfig.DbMapping dbMapping = new MappingConfig.DbMapping();
            mappingConfig.setDbMapping(dbMapping);
            dbMapping.setDatabase(dml.getDatabase());
            dbMapping.setTable(dml.getTable());
            dbMapping.setTargetDb(dml.getDatabase());
            dbMapping.setTargetTable(dml.getTable());
            dbMapping.setMapAll(true);
            List<String> pkNames = dml.getPkNames();
            Map<String, String> pkMapping = new LinkedHashMap<>();
            pkNames.forEach(pkName -> pkMapping.put(pkName, pkName));
            dbMapping.setTargetPk(pkMapping);

            mirrorDbConfig.getTableConfig().put(key, mappingConfig);
        }
    }

    /**
     * DDL 操作
     *
     * @param ddl DDL
     */
    private void executeDdl(MirrorDbConfig mirrorDbConfig, Dml ddl) {
        logger.info("执行ddl语句，数据库{} ，sql语句{}", ddl.getDatabase(), ddl.getSql());
//        try (Connection conn = dataSource.getConnection(); Statement statement = conn.createStatement()) {
//            // 替换反引号
//            String sql = ddl.getSql();
//            String backtick = SyncUtil.getBacktickByDbType(dataSource.getDbType());
//            if (!"`".equals(backtick)) {
//                sql = sql.replaceAll("`", backtick);
//            }
//
//
//            statement.execute(sql);
//            // 移除对应配置
//            mirrorDbConfig.getTableConfig().remove(ddl.getTable());
//            if (logger.isTraceEnabled()) {
//
//                logger.trace("Execute DDL sql: {} for database: {}", ddl.getSql(), ddl.getDatabase());
//            }
//
//
//        } catch (Exception e) {
//            logger.error("执行ddl语句出现异常，数据库{} ，sql语句{}，异常 {}", ddl.getDatabase(), ddl.getSql(), Throwables.getStackTraceAsString(e));
//        }

        Matcher matcher = CREATE_DATABASE_PATTERN.matcher(ddl.getSql());
        if(matcher.find()){
            try (Connection conn = dataSource.getConnection(); Statement statement = conn.createStatement()) {
                // 替换反引号
                String sql = ddl.getSql();
                String backtick = SyncUtil.getBacktickByDbType(dataSource.getDbType());
                if (!"`".equals(backtick)) {
                    sql = sql.replaceAll("`", backtick);
                }


                statement.execute(sql);
                // 移除对应配置
                mirrorDbConfig.getTableConfig().remove(ddl.getTable());
                if (logger.isTraceEnabled()) {

                    logger.trace("Execute DDL sql: {} for database: {}", ddl.getSql(), ddl.getDatabase());
                }


            } catch (Exception e) {
                logger.error("执行ddl语句出现异常，数据库{} ，sql语句{}，异常 {}", ddl.getDatabase(), ddl.getSql(), Throwables.getStackTraceAsString(e));
            }
        }else {
            Connection connection = null;
            Statement statement = null;
            try {
                Class.forName(jdbcDriver);
                connection = DriverManager.getConnection(jdbcUrl + ddl.getDatabase() + "?useUnicode=true&amp;characterEncoding=utf-8", this.jdbcUserName, this.jdbcPassword);
                statement = connection.createStatement();
                statement.execute(ddl.getSql());
                // 移除对应配置
                mirrorDbConfig.getTableConfig().remove(ddl.getTable());
            //    logger.info("执行ddl语句，数据库{} ，sql语句{}", ddl.getDatabase(), ddl.getSql());
            } catch (Exception e) {
                logger.error("执行ddl语句出现异常，数据库{} ，sql语句{} ， 异常{}", ddl.getDatabase(), ddl.getSql(),Throwables.getStackTraceAsString(e));
            } finally {
                try {
                    if (statement != null) {
                        statement.close();
                    }
                    if (connection != null) {
                        connection.close();
                    }
                } catch (SQLException throwables) {
                    throwables.printStackTrace();
                }
            }
        }

    }


}
