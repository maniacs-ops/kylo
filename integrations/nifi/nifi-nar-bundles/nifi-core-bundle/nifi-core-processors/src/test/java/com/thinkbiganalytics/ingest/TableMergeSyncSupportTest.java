/*
 * Copyright (c) 2016. Teradata Inc.
 */

package com.thinkbiganalytics.ingest;

/*
 * Copyright (c) 2016. Teradata Inc.
 */

import com.klarna.hiverunner.HiveShell;
import com.klarna.hiverunner.StandaloneHiveRunner;
import com.klarna.hiverunner.annotations.HiveProperties;
import com.klarna.hiverunner.annotations.HiveRunnerSetup;
import com.klarna.hiverunner.annotations.HiveSQL;
import com.klarna.hiverunner.config.HiveRunnerConfig;
import com.thinkbiganalytics.util.PartitionBatch;
import com.thinkbiganalytics.util.PartitionSpec;

import org.apache.commons.collections4.MapUtils;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.sql.SQLException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Vector;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(StandaloneHiveRunner.class)
public class TableMergeSyncSupportTest {

    private TableMergeSyncSupport mergeSyncSupport;

    /**
     * Explicit test class configuration of the HiveRunner runtime. See {@link HiveRunnerConfig} for further details.
     */
    @HiveRunnerSetup
    public final HiveRunnerConfig CONFIG = new HiveRunnerConfig() {{
        setHiveExecutionEngine("mr");
    }};

    /**
     * Define the script files under test. The files will be loaded in the given order. <p/> The HiveRunner instantiate and inject the HiveShell
     */
    @HiveSQL(files = {
        "hive-test-support/create_table.sql"
    }, encoding = "UTF-8")
    private HiveShell hiveShell;

    /**
     * Cater for all the parameters in the script that we want to test. Note that the "hadoop.tmp.dir" is one of the dirs defined by the test harness
     */
    @HiveProperties
    public Map<String, String> hiveProperties = MapUtils.putAll(new HashMap<String, String>(), new Object[]{
        "MY.HDFS.DIR", "${hadoop.tmp.dir}",
        "my.schema", "bar",
    });


    private String sourceTable = "emp_sr.employee_valid";
    private String targetTable = "emp_sr.employee";
    private String targetTableNP = "emp_sr.employee_np";
    private String processingPartition = "20160119074340";
    private PartitionSpec spec = new PartitionSpec("country|string|country\nyear|int|year(hired)");
    private PartitionSpec specNP = new PartitionSpec("");

    @Before
    public void setupSupport() {
        this.mergeSyncSupport = new HiveShellTableMergeSyncSupport(hiveShell);
        mergeSyncSupport.enableDynamicPartitions();
    }

    @Test
    public void testPartitionBatches() {
        List<PartitionBatch> batches = fetchPartitionBatches();
        assertTrue(batches.size() == 4);
    }

    private List<PartitionBatch> fetchPartitionBatches() {
        List<PartitionBatch> vBatch = new Vector<>();
        String sql = spec.toDistinctSelectSQL(sourceTable, processingPartition);
        List<Object[]> results = hiveShell.executeStatement(sql);
        for (Object[] vals : results) {
            vBatch.add(new PartitionBatch((Long) vals[2], spec, new String[]{vals[0].toString(), vals[1].toString()}));
        }
        return vBatch;
    }


    private List<String> fetchEmployees(String targetTable) {
        return hiveShell.executeQuery("select * from " + targetTable);
    }


    @Test
    /**
     * Tests the sync function
     */
    public void testSyncWithPartitions() throws Exception {
        doTestSync(targetTable, spec);
    }

    @Test
    /**
     * Tests the sync function
     */
    public void testSyncNonPartitioned() throws Exception {
        doTestSync(targetTableNP, specNP);
    }


    private void doTestSync(String targetTable, PartitionSpec spec) throws SQLException {
        mergeSyncSupport.doSync(sourceTable, targetTable, spec, processingPartition);
        List<String> results = fetchEmployees(targetTable);
        assertEquals(4, results.size());

        hiveShell.execute("insert into emp_sr.employee_valid partition(processing_dttm='20160119074340') (  `id`,  `name`,`company`,`zip`,`phone`,`email`,  `hired`,`country`) values (100,'Bruce',"
                          + "'ABC',"
                          + "'94550','555-1212','bruce@acme.org','2016-01-01','Canada');");

        mergeSyncSupport.doSync(sourceTable, targetTable, spec, processingPartition);
        results = fetchEmployees(targetTable);
        assertEquals(5, results.size());
    }

    @Test
    /**
     * Tests the merge partition without dedupe and the merge partition with dedupe
     */
    public void testMergePartition() throws Exception {
        // Insert one record to start
        hiveShell.execute(
            "insert into emp_sr.employee partition(country='USA',year=2015) (  `id`,  `name`,`company`,`zip`,`phone`,`email`,  `hired`)  values (1,'Sally','ABC','94550','555-1212',"
            + "'sally@acme.org','2015-01-01');");

        doTestMerge(targetTable, spec);
    }

    @Test
    /**
     * Tests the merge partition without dedupe and the merge partition with dedupe
     */
    public void testMergeNonPartitioned() throws Exception {
        // Insert one record to start
        hiveShell.execute(
            "insert into emp_sr.employee partition(country='USA',year=2015) (`id`,  `name`,`company`,`zip`,`phone`,`email`,  `hired`)  values (1,'Sally','ABC','94550','555-1212',"
            + "'sally@acme.org','2015-01-01');");

        doTestMerge(targetTableNP, specNP);
    }

    private void doTestMerge(String targetTable, PartitionSpec spec) throws SQLException {

        List<String> results = fetchEmployees(targetTable);
        assertEquals(1, results.size());

        // Call merge
        mergeSyncSupport.doMerge(sourceTable, targetTable, spec, processingPartition, false);

        // We should have 5 records 4 from the sourceTable and 1 existing
        results = fetchEmployees(targetTable);
        assertEquals(5, results.size());

        // Run merge with dedupe and should get the following two additional results. The result should not include any duplicates in the target table.
        hiveShell.execute("insert into emp_sr.employee_valid partition(processing_dttm='20160119074340') (  `id`,  `name`,`company`,`zip`,`phone`,`email`,  `hired`,`country`) values (100,'Bruce',"
                          + "'ABC',"
                          + "'94550','555-1212','bruce@acme.org','2016-01-01','Canada');");
        hiveShell.execute("insert into emp_sr.employee_valid partition(processing_dttm='20160119074340') (  `id`,  `name`,`company`,`zip`,`phone`,`email`,  `hired`,`country`) values (101,'Harry',"
                          + "'ABC',"
                          + "'94550','555-1212','harry@acme.org','2016-01-01','Canada');");

        mergeSyncSupport.doMerge(sourceTable, targetTable, spec, processingPartition, true);

        results = fetchEmployees(targetTable);
        assertEquals(6, results.size());
        // Verify no duplicates exist in the table
        HashSet<String> existing = new HashSet<>();
        for (String r : results) {
            assertFalse(existing.contains(r));
            existing.add(r);
        }

    }


}