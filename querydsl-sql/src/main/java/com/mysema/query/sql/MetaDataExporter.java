/*
 * Copyright (c) 2010 Mysema Ltd.
 * All rights reserved.
 *
 */
package com.mysema.query.sql;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mysema.codegen.CodeWriter;
import com.mysema.codegen.JavaWriter;
import com.mysema.codegen.ScalaWriter;
import com.mysema.codegen.model.ClassType;
import com.mysema.codegen.model.SimpleType;
import com.mysema.codegen.model.Type;
import com.mysema.codegen.model.TypeCategory;
import com.mysema.query.codegen.EntityType;
import com.mysema.query.codegen.Property;
import com.mysema.query.codegen.Serializer;
import com.mysema.query.codegen.SimpleSerializerConfig;
import com.mysema.query.sql.support.ForeignKeyData;
import com.mysema.query.sql.support.InverseForeignKeyData;
import com.mysema.query.sql.support.KeyDataFactory;
import com.mysema.query.sql.support.NotNullImpl;
import com.mysema.query.sql.support.PrimaryKeyData;
import com.mysema.query.sql.support.SizeImpl;

/**
 * MetadataExporter exports JDBC metadata to Querydsl query types
 *
 * @author tiwe
 */
public class MetaDataExporter {

    private static final Logger logger = LoggerFactory.getLogger(MetaDataExporter.class);

    private static final int COLUMN_NAME = 4;

    private static final int COLUMN_TYPE = 5;

    private static final int COLUMN_SIZE = 7;

    private static final int COLUMN_NULLABLE = 11;

    private static final int SCHEMA_NAME = 2;
    
    private static final int TABLE_NAME = 3;

    private static Writer writerFor(File file) {
        if (!file.getParentFile().exists() && !file.getParentFile().mkdirs()) {
            logger.error("Folder " + file.getParent() + " could not be created");
        }
        try {
            return new OutputStreamWriter(new FileOutputStream(file));
        } catch (FileNotFoundException e) {
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    private final Set<String> classes = new HashSet<String>();

    private File targetFolder;

    private String packageName = "com.example";

    @Nullable
    private String beanPackageName;

    private String namePrefix = "Q";

    private String nameSuffix = "";

    private String beanPrefix = "";

    private String beanSuffix = "";

    private boolean innerClassesForKeys;

    private NamingStrategy namingStrategy = new DefaultNamingStrategy();

    @Nullable
    private String schemaPattern, tableNamePattern;

    @Nullable
    private Serializer serializer;

    @Nullable
    private Serializer beanSerializer;

    private Configuration configuration = Configuration.DEFAULT;

    private final KeyDataFactory keyDataFactory = new KeyDataFactory();

    private boolean createScalaSources = false;

    private final Map<EntityType, Type> entityToWrapped = new HashMap<EntityType, Type>();

    public MetaDataExporter(){}

    protected EntityType createEntityType(@Nullable String schemaName, String tableName, final String className) {
        Type classTypeModel = new SimpleType(
                TypeCategory.ENTITY,
                beanPackageName + "." + beanPrefix + className + beanSuffix,
                beanPackageName,
                beanPrefix + className + beanSuffix,
                false,
                false);

        EntityType classModel;
        if (beanSerializer == null){
            classModel = new EntityType("", "", classTypeModel);
        }else{
            classModel = new EntityType(namePrefix, nameSuffix, classTypeModel){
                @Override
                public String getFullName(){
                    return packageName + "." + className;
                }
                @Override
                public String getPackageName(){
                    return packageName;
                }
                @Override
                public String getSimpleName(){
                    return className;
                }
            };
        }
        entityToWrapped.put(classModel, classTypeModel);
        if (schemaName != null){
            classModel.addAnnotation(new SchemaImpl(schemaName));
        }
        
        classModel.addAnnotation(new TableImpl(namingStrategy.normalizeTableName(tableName)));
        return classModel;
    }

    protected Property createProperty(EntityType classModel, String columnName,
            String propertyName, Type typeModel) {
        return new Property(
                classModel,
                namingStrategy.normalizeColumnName(columnName),
                propertyName,
                typeModel,
                new String[0],
                false);
    }

    /**
     * Export the tables based on the given database metadata
     *
     * @param md
     * @throws SQLException
     */
    public void export(DatabaseMetaData md) throws SQLException {
        if (serializer == null){
            serializer = new MetaDataSerializer(
                    namePrefix, nameSuffix, beanPrefix, beanSuffix,
                    beanPackageName, namingStrategy, innerClassesForKeys);
        }
        if (beanPackageName == null){
            beanPackageName = packageName;
        }

        ResultSet tables = md.getTables(null, schemaPattern, tableNamePattern, null);
        try{
            while (tables.next()) {
                handleTable(md, tables);
            }
        }finally{
            tables.close();
        }
    }

    Set<String> getClasses() {
        return classes;
    }

    private void handleColumn(EntityType classModel, String tableName, ResultSet columns) throws SQLException {
        String columnName = columns.getString(COLUMN_NAME);
        String propertyName = namingStrategy.getPropertyName(columnName, namePrefix, nameSuffix, classModel);
        Class<?> clazz = configuration.getJavaType(columns.getInt(COLUMN_TYPE), tableName, columnName);
        TypeCategory fieldType = TypeCategory.get(clazz.getName());
        if (Number.class.isAssignableFrom(clazz)){
            fieldType = TypeCategory.NUMERIC;
        }else if (Enum.class.isAssignableFrom(clazz)){
            fieldType = TypeCategory.ENUM;
        }
        Type typeModel = new ClassType(fieldType, clazz);
        Property property = createProperty(classModel, columnName, propertyName, typeModel);
        property.addAnnotation(new ColumnImpl(namingStrategy.normalizeColumnName(columnName)));
        int nullable = columns.getInt(COLUMN_NULLABLE);
        if (nullable == DatabaseMetaData.columnNoNulls){
            property.addAnnotation(new NotNullImpl());
        }
        int size = columns.getInt(COLUMN_SIZE);
        if (size > 0 && clazz.equals(String.class)){
            property.addAnnotation(new SizeImpl(0, size));
        }
        classModel.addProperty(property);
    }

    private void handleTable(DatabaseMetaData md, ResultSet tables) throws SQLException {
        String schemaName = tables.getString(SCHEMA_NAME);
        String tableName = tables.getString(TABLE_NAME);
        String className = namingStrategy.getClassName(namePrefix, nameSuffix, tableName);
        if (beanSerializer != null){
            className = className.substring(namePrefix.length(), className.length()-nameSuffix.length());
        }
        EntityType classModel = createEntityType(schemaName, tableName, className);

        // collect primary keys
        Map<String,PrimaryKeyData> primaryKeyData = keyDataFactory.getPrimaryKeys(md, schemaPattern, tableName);
        if (!primaryKeyData.isEmpty()){
            classModel.getData().put(PrimaryKeyData.class, primaryKeyData.values());
        }

        // collect foreign keys
        Map<String,ForeignKeyData> foreignKeyData = keyDataFactory.getImportedKeys(md, schemaPattern, tableName);
        if (!foreignKeyData.isEmpty()){
            classModel.getData().put(ForeignKeyData.class, foreignKeyData.values());
        }

        // collect inverse foreign keys
        Map<String,InverseForeignKeyData> inverseForeignKeyData = keyDataFactory.getExportedKeys(md, schemaPattern, tableName);
        if (!inverseForeignKeyData.isEmpty()){
            classModel.getData().put(InverseForeignKeyData.class, inverseForeignKeyData.values());
        }

        // collect columns
        ResultSet columns = md.getColumns(null, schemaPattern, tableName, null);
        try{
            while (columns.next()) {
                handleColumn(classModel, tableName, columns);
            }
        }finally{
            columns.close();
        }

        // serialize model
        serialize(classModel);

        logger.info("Exported " + tableName + " successfully");
    }

    private void serialize(EntityType type) {
        try {
            String fileSuffix = createScalaSources ? ".scala" : ".java";

            if (beanSerializer != null){
                String path = beanPackageName.replace('.', '/') + "/" + entityToWrapped.get(type).getSimpleName() + fileSuffix;
                EntityType entityForBean = new EntityType("", "", entityToWrapped.get(type));
                for (Property property : type.getProperties()){
                    entityForBean.addProperty(property);
                }
                write(beanSerializer, path, entityForBean);

                String otherPath = packageName.replace('.', '/') + "/" + namePrefix + type.getSimpleName() + nameSuffix + fileSuffix;
                write(serializer, otherPath, type);
            }else{
                String path = packageName.replace('.', '/') + "/" + type.getSimpleName() + fileSuffix;
                write(serializer, path, type);
            }

        } catch (IOException e) {
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    private void write(Serializer serializer, String path, EntityType type) throws IOException {
        File targetFile = new File(targetFolder, path);
        classes.add(targetFile.getPath());
        Writer w = writerFor(targetFile);
        try{
            CodeWriter writer = createScalaSources ? new ScalaWriter(w) : new JavaWriter(w);
            serializer.serialize(type, SimpleSerializerConfig.DEFAULT, writer);
        }finally{
            w.close();
        }
    }

    /**
     * Set the schema pattern filter to be used
     *
     * @param schemaPattern a schema name pattern; must match the schema name
     *        as it is stored in the database; "" retrieves those without a schema;
     *        <code>null</code> means that the schema name should not be used to narrow
     *        the search (default: null)
     */
    public void setSchemaPattern(String schemaPattern) {
        this.schemaPattern = schemaPattern;
    }

    /**
     * Set the table name pattern filter to be used
     *
    * @param tableNamePattern a table name pattern; must match the
    *        table name as it is stored in the database (default: null)
    */
    public void setTableNamePattern(String tableNamePattern) {
        this.tableNamePattern = tableNamePattern;
    }

    /**
     * Override the configuration
     *
     * @param configuration override configuration for custom type mappings etc
     */
    public void setConfiguration(Configuration configuration) {
        this.configuration = configuration;
    }

    /**
     * Set true to create Scala sources instead of Java sources
     *
     * @param createScalaSources whether to create Scala sources (default: false)
     */
    public void setCreateScalaSources(boolean createScalaSources) {
        this.createScalaSources = createScalaSources;
    }

    /**
     * Set the target folder
     *
     * @param targetFolder target source folder to create the sources into (e.g. target/generated-sources/java)
     */
    public void setTargetFolder(File targetFolder) {
        this.targetFolder = targetFolder;
    }

    /**
     * Set the package name
     *
     * @param packageName package name for sources
     */
    public void setPackageName(String packageName) {
        this.packageName = packageName;
    }

    /**
     * Override the bean package name (default: packageName)
     *
     * @param beanPackageName
     */
    public void setBeanPackageName(String beanPackageName) {
        this.beanPackageName = beanPackageName;
    }

    /**
     * Override the name prefix for the classes (default: Q)
     *
     * @param namePrefix name prefix for query-types (default: Q)
     */
    public void setNamePrefix(String namePrefix) {
        this.namePrefix = namePrefix;
    }

    /**
     * Override the name suffix for the classes (default: "")
     *
     * @param nameSuffix name suffix for query-types (default: "")
     */
    public void setNameSuffix(String nameSuffix) {
        this.nameSuffix = nameSuffix;
    }

    /**
     * Override the bean prefix for the classes (default: "")
     *
     * @param beanPrefix bean prefix for bean-types (default: "")
     */
    public void setBeanPrefix(String beanPrefix) {
        this.beanPrefix = beanPrefix;
    }

    /**
     * Override the bean suffix for the classes (default: "")
     *
     * @param beanSuffix bean suffix for bean-types (default: "")
     */
    public void setBeanSuffix(String beanSuffix) {
        this.beanSuffix = beanSuffix;
    }

    /**
     * Override the NamingStrategy (default: new DefaultNamingStrategy())
     *
     * @param namingStrategy namingstrategy to override (default: new DefaultNamingStrategy())
     */
    public void setNamingStrategy(NamingStrategy namingStrategy) {
        this.namingStrategy = namingStrategy;
    }

    /**
     * Override the serializer to be used (default: new MetaDataSerializer(namePrefix, namingStrategy))
     *
     * @param serializer serializer to override (default: new MetaDataSerializer(namePrefix, namingStrategy))
     */
    public void setSerializer(Serializer serializer) {
        this.serializer = serializer;
    }

    /**
     * Set the Bean serializer to create bean types as well
     *
     * @param beanSerializer serializer for JavaBeans (default: null)
     */
    public void setBeanSerializer(Serializer beanSerializer) {
        this.beanSerializer = beanSerializer;
    }

    /**
     * @param innerClassesForKeys
     */
    public void setInnerClassesForKeys(boolean innerClassesForKeys) {
        this.innerClassesForKeys = innerClassesForKeys;
    }


}
