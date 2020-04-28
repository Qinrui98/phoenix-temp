package org.apache.phoenix.schema;

import com.google.common.collect.Sets;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.commons.cli.PosixParser;
import org.apache.commons.lang.StringUtils;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.conf.Configured;
import org.apache.hadoop.hbase.HBaseConfiguration;
import org.apache.hadoop.hbase.HColumnDescriptor;
import org.apache.hadoop.hbase.HConstants;
import org.apache.hadoop.hbase.HTableDescriptor;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.io.ImmutableBytesWritable;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.util.Tool;
import org.apache.hadoop.util.ToolRunner;
import org.apache.phoenix.jdbc.PhoenixConnection;
import org.apache.phoenix.mapreduce.util.ConnectionUtil;
import org.apache.phoenix.query.ConnectionQueryServices;
import org.apache.phoenix.query.ConnectionQueryServicesImpl;
import org.apache.phoenix.query.QueryServices;
import org.apache.phoenix.query.QueryServicesOptions;
import org.apache.phoenix.util.PhoenixRuntime;
import org.apache.phoenix.util.SchemaUtil;

import java.io.FileWriter;
import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

import static org.apache.hadoop.hbase.HColumnDescriptor.BLOOMFILTER;
import static org.apache.hadoop.hbase.HColumnDescriptor.COMPRESSION;
import static org.apache.hadoop.hbase.HColumnDescriptor.DATA_BLOCK_ENCODING;
import static org.apache.hadoop.hbase.HTableDescriptor.IS_META;
import static org.apache.phoenix.util.MetaDataUtil.VIEW_INDEX_ID_COLUMN_NAME;
import static org.apache.phoenix.util.SchemaUtil.DEFAULT_DATA_BLOCK_ENCODING;

public class SchemaExtractionTool extends Configured implements Tool {

    private static final Logger LOGGER = Logger.getLogger(SchemaExtractionTool.class.getName());
    private static final Option HELP_OPTION = new Option("h", "help",
            false, "Help");
    private static final Option TABLE_OPTION = new Option("tb", "table", true,
            "[Required] Table name ex. table1");
    private static final Option SCHEMA_OPTION = new Option("s", "schema", true,
            "[Optional] Schema name ex. schema");
    private static final Option TREE_FILE_OPTION = new Option("tf", "tree-file", true,
            "[Optional] Generate schema hierarchy tree and write to file in json");

    private String pTableName;
    private String pSchemaName;
    private String treeFile;

    private static final String CREATE_TABLE = "CREATE TABLE %s";
    private static final String CREATE_INDEX = "CREATE %sINDEX %s ON %s";
    private static final String CREATE_VIEW = "CREATE VIEW %s%s AS SELECT * FROM %s%s";
    Configuration conf;
    Map<String, String> defaultProps = new HashMap<>();
    Map<String, String> definedProps = new HashMap<>();
    public String output;

    @Override
    public int run(String[] args) throws Exception {
        populateToolAttributes(args);
        conf = HBaseConfiguration.addHbaseResources(getConf());
        PTable table = getPTable(pSchemaName, pTableName);
        if (treeFile == null) {
            output = getDDL(table);
        } else {
            output = getSchemaTree(table);
            // Write JSON file
            try (FileWriter file = new FileWriter(treeFile)) {
                file.write(output);
                file.flush();
            } catch (IOException e) {
                LOGGER.info(String.format("Error writing schema tree to file: %s cause: %s",
                        treeFile, e.getMessage()));
                e.printStackTrace();
            }
        }
        return 0;
    }

    private String getDDL(PTable table) throws Exception {
        String ddl = null;
        if(table.getType().equals(PTableType.TABLE)) {
            ddl = extractCreateTableDDL(table);
        } else if(table.getType().equals(PTableType.INDEX)) {
            ddl = extractCreateIndexDDL(table);
        } else if(table.getType().equals(PTableType.VIEW)) {
            ddl = extractCreateViewDDL(table);
        }
        return ddl;
    }

    private String getSchemaTree(PTable table) throws Exception {
        SchemaTreeNode node = null;
        if(table.getType().equals(PTableType.TABLE)) {
            node = new TableSchemaTreeNode(table, this);
        } else if(table.getType().equals(PTableType.INDEX)) {
            node = new IndexSchemaTreeNode(table, this);
        } else if(table.getType().equals(PTableType.VIEW)) {
            node = new ViewSchemaTreeNode(table, this);
        }
        node.visit();
        return node.toJSON().toString(2);
    }

    protected String extractCreateIndexDDL(PTable indexPTable)
            throws SQLException {
        String baseTableName = indexPTable.getParentTableName().getString();
        String baseTableFullName = SchemaUtil.getQualifiedTableName(pSchemaName, baseTableName);
        PTable dataPTable = getPTable(baseTableFullName);

        String defaultCF = SchemaUtil.getEmptyColumnFamilyAsString(indexPTable);
        String indexedColumnsString = getIndexedColumnsString(indexPTable, dataPTable, defaultCF);
        String coveredColumnsString = getCoveredColumnsString(indexPTable, defaultCF);


        return generateIndexDDLString(baseTableFullName, indexedColumnsString, coveredColumnsString,
                indexPTable.getIndexType().equals(PTable.IndexType.LOCAL));
    }

    //TODO: Indexed on an expression
    // test with different default CF, key is a included column
    private String getIndexedColumnsString(PTable indexPTable, PTable dataPTable, String defaultCF) {

        List<PColumn> indexPK = indexPTable.getPKColumns();
        List<PColumn> dataPK = dataPTable.getPKColumns();
        Set<String> indexPkSet = new HashSet<>();
        Set<String> dataPkSet = new HashSet<>();
        Map<String, SortOrder> sortOrderMap = new HashMap<>();
        StringBuilder indexedColumnsBuilder = new StringBuilder();
        for (PColumn indexedColumn : indexPK) {
            String indexColumn = extractIndexColumn(indexedColumn.getName().getString(), defaultCF);
            if(indexColumn.equalsIgnoreCase(VIEW_INDEX_ID_COLUMN_NAME)) {
                continue;
            }
            indexPkSet.add(indexColumn);
            sortOrderMap.put(indexColumn, indexedColumn.getSortOrder());
        }

        for(PColumn pColumn : dataPK) {
            dataPkSet.add(pColumn.getName().getString());
        }

        Set<String> effectivePK = Sets.symmetricDifference(indexPkSet, dataPkSet);
        for (String column : effectivePK) {
            if(indexedColumnsBuilder.length()!=0) {
                indexedColumnsBuilder.append(", ");
            }
            indexedColumnsBuilder.append(column);
            if(sortOrderMap.get(column)!= SortOrder.getDefault()) {
                indexedColumnsBuilder.append(" ");
                indexedColumnsBuilder.append(sortOrderMap.get(column));
            }
        }
        return indexedColumnsBuilder.toString();
    }

    private String extractIndexColumn(String columnName, String defaultCF) {
        String [] columnNameSplit = columnName.split(":");
        if(columnNameSplit[0].equals("") || columnNameSplit[0].equalsIgnoreCase(defaultCF)) {
            return columnNameSplit[1];
        } else {
            return columnName.replace(":", ".");
        }
    }

    private String getCoveredColumnsString(PTable indexPTable, String defaultCF) {
        StringBuilder coveredColumnsBuilder = new StringBuilder();
        List<PColumn> pkColumns = indexPTable.getColumns();
        for (PColumn cc : pkColumns) {
            if(coveredColumnsBuilder.length()!=0) {
                coveredColumnsBuilder.append(", ");
            }
            if(cc.getFamilyName()!=null) {
                String indexColumn = extractIndexColumn(cc.getName().getString(), defaultCF);
                coveredColumnsBuilder.append(indexColumn);
            }
        }
        return coveredColumnsBuilder.toString();
    }

    protected String generateIndexDDLString(String baseTableFullName, String indexedColumnString, String coveredColumnString, boolean local) {
        StringBuilder outputBuilder = new StringBuilder(String.format(CREATE_INDEX, local ? "LOCAL " : "", pTableName, baseTableFullName));
        outputBuilder.append("(");
        outputBuilder.append(indexedColumnString);
        outputBuilder.append(")");
        if(!coveredColumnString.equals("")) {
            outputBuilder.append(" INCLUDE (");
            outputBuilder.append(coveredColumnString);
            outputBuilder.append(")");
        }
        return outputBuilder.toString();
    }

    private PTable getPTable(String pTableFullName) throws SQLException {
        try (Connection conn = getConnection(conf)) {
            return PhoenixRuntime.getTable(conn, pTableFullName);
        }
    }

    private String extractCreateViewDDL(PTable table) throws SQLException {
        String baseTableName = table.getParentTableName().getString();
        String baseTableFullName = SchemaUtil.getQualifiedTableName(pSchemaName, baseTableName);
        PTable baseTable = getPTable(baseTableFullName);
        String columnInfoString = getColumnInfoString(table, baseTable);

        String whereClause = table.getViewStatement();
        if(whereClause != null) {
            whereClause = whereClause.substring(whereClause.indexOf("WHERE"));
        }
        return generateCreateViewDDL(columnInfoString, baseTableFullName, whereClause == null ? "" : " "+whereClause);
    }

    private String generateCreateViewDDL(String columnInfoString, String baseTableFullName, String whereClause) {
        String viewFullName = SchemaUtil.getQualifiedTableName(pSchemaName, pTableName);
        StringBuilder outputBuilder = new StringBuilder(String.format(CREATE_VIEW, viewFullName, columnInfoString, baseTableFullName, whereClause));
        return outputBuilder.toString();
    }

    public String extractCreateTableDDL(PTable table) throws IOException, SQLException {
        ConnectionQueryServicesImpl cqsi = (ConnectionQueryServicesImpl) getCQSIObject();
        HTableDescriptor htd = getHTableDescriptor(cqsi, table);
        HColumnDescriptor hcd = htd.getFamily(SchemaUtil.getEmptyColumnFamily(table));

        populateDefaultProperties(table);
        setPTableProperties(table);
        setHTableProperties(htd);
        setHColumnFamilyProperties(hcd);

        String columnInfoString = getColumnInfoString(table, null);
        String propertiesString = convertPropertiesToString();

        return generateTableDDLString(columnInfoString, propertiesString);
    }

    private String generateTableDDLString(String columnInfoString, String propertiesString) {
        String pTableFullName = SchemaUtil.getQualifiedTableName(pSchemaName, pTableName);
        StringBuilder outputBuilder = new StringBuilder(String.format(CREATE_TABLE, pTableFullName));
        outputBuilder.append(columnInfoString).append(propertiesString);
        return outputBuilder.toString();
    }

    private void populateDefaultProperties(PTable table) {
        Map<String, String> propsMap = HColumnDescriptor.getDefaultValues();
        for (Map.Entry<String, String> entry : propsMap.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            defaultProps.put(key, value);
            if(key.equalsIgnoreCase(BLOOMFILTER) || key.equalsIgnoreCase(COMPRESSION)) {
                defaultProps.put(key, "NONE");
            }
            if(key.equalsIgnoreCase(DATA_BLOCK_ENCODING)) {
                defaultProps.put(key, String.valueOf(DEFAULT_DATA_BLOCK_ENCODING));
            }
        }
        defaultProps.putAll(table.getDefaultValues());
    }

    private void setHTableProperties(HTableDescriptor htd) {
        Map<ImmutableBytesWritable, ImmutableBytesWritable> propsMap = htd.getValues();
        for (Map.Entry<ImmutableBytesWritable, ImmutableBytesWritable> entry : propsMap.entrySet()) {
            ImmutableBytesWritable key = entry.getKey();
            ImmutableBytesWritable value = entry.getValue();
            if(Bytes.toString(key.get()).contains("coprocessor") || Bytes.toString(key.get()).contains(IS_META)) {
                continue;
            }
            defaultProps.put(Bytes.toString(key.get()), "false");
            definedProps.put(Bytes.toString(key.get()), Bytes.toString(value.get()));
        }
    }

    private void setHColumnFamilyProperties(HColumnDescriptor columnDescriptor) {
        Map<ImmutableBytesWritable, ImmutableBytesWritable> propsMap = columnDescriptor.getValues();
        for (Map.Entry<ImmutableBytesWritable, ImmutableBytesWritable> entry : propsMap.entrySet()) {
            ImmutableBytesWritable key = entry.getKey();
            ImmutableBytesWritable value = entry.getValue();
            definedProps.put(Bytes.toString(key.get()), Bytes.toString(value.get()));
        }
    }

    private void setPTableProperties(PTable table) {
        Map <String, String> map = table.getValues();
        for(Map.Entry<String, String> entry : map.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            if(value != null) {
                definedProps.put(key, value);
            }
        }
    }

    private HTableDescriptor getHTableDescriptor(ConnectionQueryServicesImpl cqsi, PTable table)
            throws SQLException, IOException {
        return cqsi.getAdmin().getTableDescriptor(
                TableName.valueOf(table.getPhysicalName().getString()));
    }

    private String convertPropertiesToString() {
        StringBuilder optionBuilder = new StringBuilder();

        for(Map.Entry<String, String> entry : definedProps.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            if(value!=null && defaultProps.get(key) != null && !value.equals(defaultProps.get(key))) {
                if (optionBuilder.length() != 0) {
                    optionBuilder.append(",");
                }
                optionBuilder.append(key+"="+value);
            }
        }
        return optionBuilder.toString();
    }

    private PTable getPTable(String pSchemaName, String pTableName) throws SQLException {
        String pTableFullName = SchemaUtil.getQualifiedTableName(pSchemaName, pTableName);
        return getPTable(pTableFullName);
    }

    private ConnectionQueryServices getCQSIObject() throws SQLException {
        try(Connection conn = getConnection(conf)) {
            return conn.unwrap(PhoenixConnection.class).getQueryServices();
        }
    }

    public static Connection getConnection(Configuration conf) throws SQLException {
        // in case we want to query sys cat
        setRpcRetriesAndTimeouts(conf);
        return ConnectionUtil.getInputConnection(conf);
    }

    private static void setRpcRetriesAndTimeouts(Configuration conf) {
        long indexRebuildQueryTimeoutMs =
                conf.getLong(QueryServices.INDEX_REBUILD_QUERY_TIMEOUT_ATTRIB,
                        QueryServicesOptions.DEFAULT_INDEX_REBUILD_QUERY_TIMEOUT);
        long indexRebuildRPCTimeoutMs =
                conf.getLong(QueryServices.INDEX_REBUILD_RPC_TIMEOUT_ATTRIB,
                        QueryServicesOptions.DEFAULT_INDEX_REBUILD_RPC_TIMEOUT);
        long indexRebuildClientScannerTimeOutMs =
                conf.getLong(QueryServices.INDEX_REBUILD_CLIENT_SCANNER_TIMEOUT_ATTRIB,
                        QueryServicesOptions.DEFAULT_INDEX_REBUILD_CLIENT_SCANNER_TIMEOUT);
        int indexRebuildRpcRetriesCounter =
                conf.getInt(QueryServices.INDEX_REBUILD_RPC_RETRIES_COUNTER,
                        QueryServicesOptions.DEFAULT_INDEX_REBUILD_RPC_RETRIES_COUNTER);

        // Set phoenix and hbase level timeouts and rpc retries
        conf.setLong(QueryServices.THREAD_TIMEOUT_MS_ATTRIB, indexRebuildQueryTimeoutMs);
        conf.setLong(HConstants.HBASE_RPC_TIMEOUT_KEY, indexRebuildRPCTimeoutMs);
        conf.setLong(HConstants.HBASE_CLIENT_SCANNER_TIMEOUT_PERIOD,
                indexRebuildClientScannerTimeOutMs);
        conf.setInt(HConstants.HBASE_CLIENT_RETRIES_NUMBER, indexRebuildRpcRetriesCounter);
    }

    private String getColumnInfoString(PTable table, PTable baseTable) {
        StringBuilder colInfo = new StringBuilder();
        colInfo.append('(');

        List<PColumn> columns = table.getColumns();
        List<PColumn> pkColumns = table.getPKColumns();

        if (baseTable != null) {
            Set<PColumn> columnSet = new HashSet<>(columns);
            Set<PColumn> pkSet = new HashSet<>(pkColumns);

            List<PColumn> baseColumns = baseTable.getColumns();
            List<PColumn> basePkColumns = baseTable.getPKColumns();

            Set<PColumn> baseColumnSet = new HashSet<>(baseColumns);
            Set<PColumn> basePkSet = new HashSet<>(basePkColumns);

            Set<PColumn> columnsSet = Sets.symmetricDifference(baseColumnSet, columnSet);
            Set<PColumn> pkColumnsSet = Sets.symmetricDifference(basePkSet, pkSet);

            columns = new ArrayList<>(columnsSet);
            pkColumns = new ArrayList<>(pkColumnsSet);

        }
        ArrayList<String> colDefs = new ArrayList<>(columns.size());
        for (PColumn col : columns) {
            String def = extractColumn(col);
            if (pkColumns.size() == 1 && pkColumns.contains(col)) {
                def += " PRIMARY KEY" + extractPKColumnAttributes(col);
            }
            colDefs.add(def);
        }
        colInfo.append(StringUtils.join(colDefs, ", "));

        if (pkColumns.size() > 1) {
            // multi column primary key
            String pkConstraint = String.format(" CONSTRAINT %s PRIMARY KEY (%s)",
                    table.getPKName().getString(), extractPKConstraint(pkColumns));
            colInfo.append(pkConstraint);
        }
        colInfo.append(')');
        return colInfo.toString();
    }

    private String extractColumn(PColumn column) {
        String colName = column.getName().getString();
        String type = column.getDataType().getSqlTypeName();
        StringBuilder buf = new StringBuilder(colName);
        buf.append(' ');
        buf.append(type);
        Integer maxLength = column.getMaxLength();
        if (maxLength != null) {
            buf.append('(');
            buf.append(maxLength);
            Integer scale = column.getScale();
            if (scale != null) {
                buf.append(',');
                buf.append(scale); // has both max length and scale. For ex- decimal(10,2)
            }
            buf.append(')');
        }

        if (!column.isNullable()) {
            buf.append(' ');
            buf.append("NOT NULL");
        }

        return buf.toString();
    }

    private String extractPKColumnAttributes(PColumn column) {
        StringBuilder buf = new StringBuilder();

        if (column.getSortOrder() != SortOrder.getDefault()) {
            buf.append(' ');
            buf.append(column.getSortOrder().toString());
        }

        if (column.isRowTimestamp()) {
            buf.append(' ');
            buf.append("ROW_TIMESTAMP");
        }

        return buf.toString();
    }

    private String extractPKConstraint(List<PColumn> pkColumns) {
        ArrayList<String> colDefs = new ArrayList<>(pkColumns.size());
        for (PColumn pkCol : pkColumns) {
            colDefs.add(pkCol.getName().getString() + extractPKColumnAttributes(pkCol));
        }
        return StringUtils.join(colDefs, ", ");
    }

    private void populateToolAttributes(String[] args) {
        try {
            CommandLine cmdLine = parseOptions(args);
            pTableName = cmdLine.getOptionValue(TABLE_OPTION.getOpt());
            pSchemaName = cmdLine.getOptionValue(SCHEMA_OPTION.getOpt());
            treeFile = cmdLine.getOptionValue(TREE_FILE_OPTION.getOpt());
            LOGGER.info("Schema Extraction Tool initiated: " + StringUtils.join( args, ","));
        } catch (IllegalStateException e) {
            printHelpAndExit(e.getMessage(), getOptions());
        }
    }

    private CommandLine parseOptions(String[] args) {
        final Options options = getOptions();
        CommandLineParser parser = new PosixParser();
        CommandLine cmdLine = null;
        try {
            cmdLine = parser.parse(options, args);
        } catch (ParseException e) {
            printHelpAndExit("severe parsing command line options: " + e.getMessage(),
                    options);
        }
        if (cmdLine.hasOption(HELP_OPTION.getOpt())) {
            printHelpAndExit(options, 0);
        }
        if (!(cmdLine.hasOption(TABLE_OPTION.getOpt()))) {
            throw new IllegalStateException("Table name should be passed "
                    +TABLE_OPTION.getLongOpt());
        }
        return cmdLine;
    }

    private Options getOptions() {
        final Options options = new Options();
        options.addOption(TABLE_OPTION);
        SCHEMA_OPTION.setOptionalArg(true);
        options.addOption(SCHEMA_OPTION);
        options.addOption(TREE_FILE_OPTION);
        TREE_FILE_OPTION.setOptionalArg(true);
        return options;
    }

    private void printHelpAndExit(String severeMessage, Options options) {
        System.err.println(severeMessage);
        printHelpAndExit(options, 1);
    }

    private void printHelpAndExit(Options options, int exitCode) {
        HelpFormatter formatter = new HelpFormatter();
        formatter.printHelp("help", options);
        System.exit(exitCode);
    }

    public static void main (String[] args) throws Exception {
        int result = ToolRunner.run(new SchemaExtractionTool(), args);
        System.exit(result);
    }
}