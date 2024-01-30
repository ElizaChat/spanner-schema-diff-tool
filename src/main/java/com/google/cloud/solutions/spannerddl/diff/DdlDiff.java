/*
 * Copyright 2020 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.cloud.solutions.spannerddl.diff;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.auto.value.AutoValue;
import com.google.cloud.solutions.spannerddl.parser.ASTadd_row_deletion_policy;
import com.google.cloud.solutions.spannerddl.parser.ASTalter_database_statement;
import com.google.cloud.solutions.spannerddl.parser.ASTalter_table_statement;
import com.google.cloud.solutions.spannerddl.parser.ASTcheck_constraint;
import com.google.cloud.solutions.spannerddl.parser.ASTcolumn_def;
import com.google.cloud.solutions.spannerddl.parser.ASTcolumn_default_clause;
import com.google.cloud.solutions.spannerddl.parser.ASTcolumn_type;
import com.google.cloud.solutions.spannerddl.parser.ASTcreate_change_stream_statement;
import com.google.cloud.solutions.spannerddl.parser.ASTcreate_index_statement;
import com.google.cloud.solutions.spannerddl.parser.ASTcreate_table_statement;
import com.google.cloud.solutions.spannerddl.parser.ASTddl_statement;
import com.google.cloud.solutions.spannerddl.parser.ASTforeign_key;
import com.google.cloud.solutions.spannerddl.parser.ASToptions_clause;
import com.google.cloud.solutions.spannerddl.parser.ASTrow_deletion_policy_clause;
import com.google.cloud.solutions.spannerddl.parser.DdlParser;
import com.google.cloud.solutions.spannerddl.parser.DdlParserTreeConstants;
import com.google.cloud.solutions.spannerddl.parser.ParseException;
import com.google.cloud.solutions.spannerddl.parser.SimpleNode;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.MapDifference;
import com.google.common.collect.MapDifference.ValueDifference;
import com.google.common.collect.Maps;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Compares two Cloud Spanner Schema (DDL) files, and can generate the ALTER statements to convert
 * one to the other.
 *
 * <p>Example usage:
 *
 * <p>Pass the original and new DDL text to the {@link #build(String, String)} function, and call
 * {@link #generateDifferenceStatements(Map)} to generate the list of {@code ALTER} statements.
 *
 * <p>eg:
 *
 * <pre>
 * List&lt;String&gt; statements = DdlDiff.build(originalDDL, newDDL)
 *    .generateDifferenceStatements(true, true);
 * </pre>
 *
 * or execute the {@link #main(String[]) main()} function with the {@link
 * DdlDiffOptions#buildOptions() appropriate command line options}.
 */
public class DdlDiff {

  private static final Logger LOG = LoggerFactory.getLogger(DdlDiff.class);
  public static final String ORIGINAL_DDL_FILE_OPT = "originalDdlFile";
  public static final String NEW_DDL_FILE_OPT = "newDdlFile";
  public static final String OUTPUT_DDL_FILE_OPT = "outputDdlFile";
  public static final String ALLOW_RECREATE_INDEXES_OPT = "allowRecreateIndexes";
  public static final String ALLOW_RECREATE_CONSTRAINTS_OPT = "allowRecreateConstraints";
  public static final String ALLOW_DROP_STATEMENTS_OPT = "allowDropStatements";
  public static final String HELP_OPT = "help";

  private final DatbaseDefinition originalDb;
  private final DatbaseDefinition newDb;
  private final MapDifference<String, ASTcreate_index_statement> indexDifferences;
  private final MapDifference<String, ASTcreate_table_statement> tableDifferences;
  private final MapDifference<String, ConstraintWrapper> constraintDifferences;
  private final MapDifference<String, ASTrow_deletion_policy_clause> ttlDifferences;
  private final MapDifference<String, String> alterDatabaseOptionsDifferences;
  private final MapDifference<String, ASTcreate_change_stream_statement> changeStreamDifferences;
  private final String databaseName; // for alter Database

  private DdlDiff(DatbaseDefinition originalDb, DatbaseDefinition newDb, String databaseName)
      throws DdlDiffException {
    this.originalDb = originalDb;
    this.newDb = newDb;
    this.databaseName = databaseName;

    this.tableDifferences =
        Maps.difference(originalDb.tablesInCreationOrder(), newDb.tablesInCreationOrder());
    this.indexDifferences = Maps.difference(originalDb.indexes(), newDb.indexes());
    this.constraintDifferences = Maps.difference(originalDb.constraints(), newDb.constraints());
    this.ttlDifferences = Maps.difference(originalDb.ttls(), newDb.ttls());
    this.alterDatabaseOptionsDifferences =
        Maps.difference(originalDb.alterDatabaseOptions(), newDb.alterDatabaseOptions());
    this.changeStreamDifferences =
        Maps.difference(originalDb.changeStreams(), newDb.changeStreams());

    if (!alterDatabaseOptionsDifferences.areEqual() && Strings.isNullOrEmpty(databaseName)) {
      // should never happen, but...
      throw new DdlDiffException("No database ID defined - required for Alter Database statements");
    }
  }

  public List<String> generateDifferenceStatements(Map<String, Boolean> options)
      throws DdlDiffException {
    ImmutableList.Builder<String> output = ImmutableList.builder();

    boolean allowDropStatements = options.get(ALLOW_DROP_STATEMENTS_OPT);

    if (!indexDifferences.entriesDiffering().isEmpty()
        && !options.get(ALLOW_RECREATE_INDEXES_OPT)) {
      throw new DdlDiffException(
          "At least one Index differs, and "
              + ALLOW_RECREATE_INDEXES_OPT
              + " is not set.\n"
              + "Indexes: "
              + Joiner.on(", ").join(indexDifferences.entriesDiffering().keySet()));
    }

    if (!constraintDifferences.entriesDiffering().isEmpty()
        && !options.get(ALLOW_RECREATE_CONSTRAINTS_OPT)) {
      throw new DdlDiffException(
          "At least one constraint differs, and "
              + ALLOW_RECREATE_CONSTRAINTS_OPT
              + " is not set.\n"
              + Joiner.on(", ").join(constraintDifferences.entriesDiffering().keySet()));
    }

    // check for modified Alter Database statements
    if (!alterDatabaseOptionsDifferences.areEqual()) {
      String optionsUpdates = generateOptionsUpdates(alterDatabaseOptionsDifferences);
      if (!Strings.isNullOrEmpty(optionsUpdates)) {
        LOG.info("Updating database options");
        output.add("ALTER DATABASE " + databaseName + " SET OPTIONS (" + optionsUpdates + ")");
      }
    }

    // Drop deleted indexes.
    if (allowDropStatements) {
      // Drop deleted indexes.
      for (String indexName : indexDifferences.entriesOnlyOnLeft().keySet()) {
        LOG.info("Dropping deleted index: {}", indexName);
        output.add("DROP INDEX " + indexName);
      }
    }

    // Drop deleted change streams.
    if (allowDropStatements) {
      // Drop deleted indexes.
      for (String changeStreamName : changeStreamDifferences.entriesOnlyOnLeft().keySet()) {
        LOG.info("Dropping deleted change stream: {}", changeStreamName);
        output.add("DROP CHANGE STREAM " + changeStreamName);
      }
    }

    // Drop modified indexes that need to be re-created...
    for (String indexName : indexDifferences.entriesDiffering().keySet()) {
      LOG.info("Dropping changed index for re-creation: {}", indexName);
      output.add("DROP INDEX " + indexName);
    }

    // Drop deleted constraints
    for (ConstraintWrapper fk : constraintDifferences.entriesOnlyOnLeft().values()) {
      LOG.info("Dropping constraint: {}", fk.getName());
      output.add("ALTER TABLE " + fk.tableName() + " DROP CONSTRAINT " + fk.getName());
    }

    // Drop modified constraints that need to be re-created...
    for (ValueDifference<ConstraintWrapper> fkDiff :
        constraintDifferences.entriesDiffering().values()) {
      LOG.info("Dropping changed constraint for re-creation: {}", fkDiff.leftValue().getName());
      output.add(
          "ALTER TABLE "
              + fkDiff.leftValue().tableName()
              + " DROP CONSTRAINT "
              + fkDiff.leftValue().getName());
    }

    // Drop deleted TTLs
    for (String tableName : ttlDifferences.entriesOnlyOnLeft().keySet()) {
      LOG.info("Dropping row deletion policy for : {}", tableName);
      output.add("ALTER TABLE " + tableName + " DROP ROW DELETION POLICY");
    }

    if (allowDropStatements) {
      // Drop tables that have been deleted -- need to do it in reverse creation order.
      List<String> reverseOrderedTableNames =
          new ArrayList<>(originalDb.tablesInCreationOrder().keySet());
      Collections.reverse(reverseOrderedTableNames);
      for (String tableName : reverseOrderedTableNames) {
        if (tableDifferences.entriesOnlyOnLeft().containsKey(tableName)) {
          LOG.info("Dropping deleted table: {}", tableName);
          output.add("DROP TABLE " + tableName);
        }
      }
    }

    // Alter existing tables, or error if not possible.
    for (ValueDifference<ASTcreate_table_statement> difference :
        tableDifferences.entriesDiffering().values()) {
      LOG.info("Altering modified table: {}", difference.leftValue().getTableName());
      output.addAll(
          generateAlterTableStatements(difference.leftValue(), difference.rightValue(), options));
    }

    // Create new tables. Must be done in the order of creation in the new DDL.
    for (Map.Entry<String, ASTcreate_table_statement> newTableEntry :
        newDb.tablesInCreationOrder().entrySet()) {
      if (tableDifferences.entriesOnlyOnRight().containsKey(newTableEntry.getKey())) {
        LOG.info("Creating new table: {}", newTableEntry.getKey());
        output.add(newTableEntry.getValue().toStringOptionalExistClause(false));
      }
    }

    // Create new TTLs
    for (Map.Entry<String, ASTrow_deletion_policy_clause> newTtl :
        ttlDifferences.entriesOnlyOnRight().entrySet()) {
      LOG.info("Adding new row deletion policy for : {}", newTtl.getKey());
      output.add("ALTER TABLE " + newTtl.getKey() + " ADD " + newTtl.getValue());
    }

    // update existing TTLs
    for (Entry<String, ValueDifference<ASTrow_deletion_policy_clause>> differentTtl :
        ttlDifferences.entriesDiffering().entrySet()) {
      LOG.info("Updating row deletion policy for : {}", differentTtl.getKey());
      output.add(
          "ALTER TABLE "
              + differentTtl.getKey()
              + " REPLACE "
              + differentTtl.getValue().rightValue());
    }

    // Create new indexes
    for (ASTcreate_index_statement index : indexDifferences.entriesOnlyOnRight().values()) {
      LOG.info("Creating new index: {}", index.getIndexName());
      output.add(index.toStringOptionalExistClause(false));
    }

    // Re-create modified indexes...
    for (ValueDifference<ASTcreate_index_statement> difference :
        indexDifferences.entriesDiffering().values()) {
      LOG.info("Re-creating changed index: {}", difference.leftValue().getIndexName());
      output.add(difference.rightValue().toStringOptionalExistClause(false));
    }

    // Create new constraints.
    for (ConstraintWrapper fk : constraintDifferences.entriesOnlyOnRight().values()) {
      LOG.info("Creating new constraint: {}", fk.getName());
      output.add("ALTER TABLE " + fk.tableName() + " ADD " + fk.constraint());
    }

    // Re-create modified constraints.
    for (ValueDifference<ConstraintWrapper> constraintDiff :
        constraintDifferences.entriesDiffering().values()) {
      LOG.info("Re-creating changed constraint: {}", constraintDiff.rightValue().getName());
      output.add(
          "ALTER TABLE "
              + constraintDiff.rightValue().tableName()
              + " ADD "
              + constraintDiff.rightValue().constraint().toString());
    }

    // Create new change streams
    for (ASTcreate_change_stream_statement newChangeStream :
        changeStreamDifferences.entriesOnlyOnRight().values()) {
      LOG.info("Creating new change stream: {}", newChangeStream.getName());
      output.add(newChangeStream.toString());
    }

    // Alter existing change streams
    for (ValueDifference<ASTcreate_change_stream_statement> changedChangeStream :
        changeStreamDifferences.entriesDiffering().values()) {
      LOG.info("Updating change stream: {}", changedChangeStream.rightValue().getName());
      String oldForClause = changedChangeStream.leftValue().getForClause().toString();
      String newForClause = changedChangeStream.rightValue().getForClause().toString();

      String oldOptions = changedChangeStream.leftValue().getOptionsClause().toString();
      String newOptions = changedChangeStream.rightValue().getOptionsClause().toString();

      if (!oldForClause.equals(newForClause)) {
        output.add(
            "ALTER CHANGE STREAM "
                + changedChangeStream.rightValue().getName()
                + " SET "
                + newForClause);
      }
      if (!oldOptions.equals(newOptions)) {
        output.add(
            "ALTER CHANGE STREAM "
                + changedChangeStream.rightValue().getName()
                + " SET "
                + newOptions);
      }
    }

    return output.build();
  }

  @VisibleForTesting
  static List<String> generateAlterTableStatements(
      ASTcreate_table_statement left, ASTcreate_table_statement right, Map<String, Boolean> options)
      throws DdlDiffException {
    ArrayList<String> alterStatements = new ArrayList<>();

    // Alter Table can:
    //   - Add constraints
    //   - drop constraints
    //   - Add cols
    //   - drop cols (if enabled)
    //   - change on-delete action for interleaved
    // ALTER TABLE ALTER COLUMN can:
    //   - change options on column
    //   - change not null on column.
    // note that constraints need to be dropped before columns, and created after columns.

    // Check interleaving has not changed.
    if (left.getInterleaveClause().isPresent() != right.getInterleaveClause().isPresent()) {
      throw new DdlDiffException("Cannot change interleaving on table " + left.getTableName());
    }

    if (left.getInterleaveClause().isPresent()
        && !(left.getInterleaveClause()
            .get()
            .getParentTableName()
            .equals(right.getInterleaveClause().get().getParentTableName()))) {
      throw new DdlDiffException(
          "Cannot change interleaved parent of table " + left.getTableName());
    }

    // Check Key is same
    if (!left.getPrimaryKey().toString().equals(right.getPrimaryKey().toString())) {
      throw new DdlDiffException("Cannot change primary key of table " + left.getTableName());
    }

    // On delete changed
    if (left.getInterleaveClause().isPresent()
        && !(left.getInterleaveClause()
            .get()
            .getOnDelete()
            .equals(right.getInterleaveClause().get().getOnDelete()))) {
      alterStatements.add(
          "ALTER TABLE "
              + left.getTableName()
              + " SET "
              + right.getInterleaveClause().get().getOnDelete());
    }

    // compare columns.
    MapDifference<String, ASTcolumn_def> columnDifferences =
        Maps.difference(left.getColumns(), right.getColumns());

    if (options.get(ALLOW_DROP_STATEMENTS_OPT)) {
      for (String columnName : columnDifferences.entriesOnlyOnLeft().keySet()) {
        alterStatements.add("ALTER TABLE " + left.getTableName() + " DROP COLUMN " + columnName);
      }
    }

    for (ASTcolumn_def column : columnDifferences.entriesOnlyOnRight().values()) {
      alterStatements.add(
          "ALTER TABLE " + left.getTableName() + " ADD COLUMN " + column.toString());
    }

    for (ValueDifference<ASTcolumn_def> columnDiff :
        columnDifferences.entriesDiffering().values()) {
      addColumnDiffs(left.getTableName(), alterStatements, columnDiff);
    }

    return alterStatements;
  }

  private static void addColumnDiffs(
      String tableName,
      ArrayList<String> alterStatements,
      ValueDifference<ASTcolumn_def> columnDiff)
      throws DdlDiffException {

    // check for compatible type changes.
    if (!columnDiff
        .leftValue()
        .getColumnTypeString()
        .equals(columnDiff.rightValue().getColumnTypeString())) {

      // check for changing lengths of Strings or Arrays - for arrays we need the 'root' type and
      // the depth.
      ASTcolumn_type leftRootType = columnDiff.leftValue().getColumnType();
      int leftArrayDepth = 0;
      while (leftRootType.isArray()) {
        leftRootType = leftRootType.getArraySubType();
        leftArrayDepth++;
      }
      ASTcolumn_type rightRootType = columnDiff.rightValue().getColumnType();
      int rightArrayDepth = 0;
      while (rightRootType.isArray()) {
        rightRootType = rightRootType.getArraySubType();
        rightArrayDepth++;
      }

      if (leftArrayDepth != rightArrayDepth
          || !leftRootType.getTypeName().equals(rightRootType.getTypeName())
          || (!leftRootType.getTypeName().equals("STRING")
              && !leftRootType.getTypeName().equals("BYTES"))) {
        throw new DdlDiffException(
            "Cannot change type of table "
                + tableName
                + " column "
                + columnDiff.leftValue().getColumnName()
                + " from "
                + columnDiff.leftValue().getColumnTypeString()
                + " to "
                + columnDiff.rightValue().getColumnTypeString());
      }
    }

    // check generated column diffs
    // check for compatible type changes.
    if (!Objects.equals(
        Objects.toString(columnDiff.leftValue().getGenerationClause()),
        Objects.toString(columnDiff.rightValue().getGenerationClause()))) {
      throw new DdlDiffException(
          "Cannot change generation clause of table "
              + tableName
              + " column "
              + columnDiff.leftValue().getColumnName()
              + " from "
              + columnDiff.leftValue().getGenerationClause()
              + " to "
              + columnDiff.rightValue().getGenerationClause());
    }

    // Not null or type length limit change.
    if (columnDiff.leftValue().isNotNull() != columnDiff.rightValue().isNotNull()
        || !columnDiff
            .leftValue()
            .getColumnTypeString()
            .equals(columnDiff.rightValue().getColumnTypeString())) {
      alterStatements.add(
          Joiner.on(" ")
              .skipNulls()
              .join(
                  "ALTER TABLE",
                  tableName,
                  "ALTER COLUMN",
                  columnDiff.rightValue().getColumnName(),
                  columnDiff.rightValue().getColumnTypeString(),
                  (columnDiff.rightValue().isNotNull() ? "NOT NULL" : null)));
    }

    // Update options.
    ASToptions_clause leftOptionsClause = columnDiff.leftValue().getOptionsClause();
    ASToptions_clause rightOptionsClause = columnDiff.rightValue().getOptionsClause();
    Map<String, String> leftOptions =
        leftOptionsClause == null ? Collections.emptyMap() : leftOptionsClause.getKeyValueMap();
    Map<String, String> rightOptions =
        rightOptionsClause == null ? Collections.emptyMap() : rightOptionsClause.getKeyValueMap();
    MapDifference<String, String> optionsDiff = Maps.difference(leftOptions, rightOptions);

    String updateText = generateOptionsUpdates(optionsDiff);

    if (!Strings.isNullOrEmpty(updateText)) {
      alterStatements.add(
          "ALTER TABLE "
              + tableName
              + " ALTER COLUMN "
              + columnDiff.rightValue().getColumnName()
              + " SET OPTIONS ("
              + updateText
              + ")");
    }

    // Update default values

    final ASTcolumn_default_clause oldDefaultValue =
        columnDiff.leftValue().getColumnDefaultClause();
    final ASTcolumn_default_clause newDefaultValue =
        columnDiff.rightValue().getColumnDefaultClause();
    if (!Objects.equals(oldDefaultValue, newDefaultValue)) {
      if (newDefaultValue == null) {
        alterStatements.add(
            "ALTER TABLE "
                + tableName
                + " ALTER COLUMN "
                + columnDiff.rightValue().getColumnName()
                + " DROP DEFAULT");
      } else {
        // add or change default value
        alterStatements.add(
            "ALTER TABLE "
                + tableName
                + " ALTER COLUMN "
                + columnDiff.rightValue().getColumnName()
                + " SET "
                + newDefaultValue);
      }
    }
  }

  private static String generateOptionsUpdates(MapDifference<String, String> optionsDiff) {

    if (optionsDiff.areEqual()) {
      return null;
    } else {
      TreeMap<String, String> optionsToUpdate = new TreeMap<>();

      // remove options only in left by setting value to null
      optionsDiff.entriesOnlyOnLeft().keySet().forEach(k -> optionsToUpdate.put(k, "NULL"));
      // add all modified to update
      optionsDiff
          .entriesDiffering()
          .forEach((key, value) -> optionsToUpdate.put(key, value.rightValue()));
      // add all new
      optionsToUpdate.putAll(optionsDiff.entriesOnlyOnRight());
      return Joiner.on(",")
          .join(
              optionsToUpdate.entrySet().stream()
                  .map(e -> e.getKey() + "=" + e.getValue())
                  .iterator());
    }
  }

  static DdlDiff build(String originalDDL, String newDDL) throws DdlDiffException {
    List<ASTddl_statement> originalStatements;
    List<ASTddl_statement> newStatements;
    try {
      originalStatements = parseDDL(Strings.nullToEmpty(originalDDL));
    } catch (DdlDiffException e) {
      throw new DdlDiffException("Failed parsing ORIGINAL DDL: " + e.getMessage(), e);
    }
    try {
      newStatements = parseDDL(Strings.nullToEmpty(newDDL));
    } catch (DdlDiffException e) {
      throw new DdlDiffException("Failed parsing NEW DDL: " + e.getMessage(), e);
    }

    DatbaseDefinition originalDb = DatbaseDefinition.create(originalStatements);
    DatbaseDefinition newDb = DatbaseDefinition.create(newStatements);

    return new DdlDiff(
        originalDb, newDb, getDatabaseNameFromAlterDatabase(originalStatements, newStatements));
  }

  private static String getDatabaseNameFromAlterDatabase(
      List<ASTddl_statement> originalStatements, List<ASTddl_statement> newStatements)
      throws DdlDiffException {
    String originalName = getDatabaseNameFromAlterDatabase(originalStatements);
    String newName = getDatabaseNameFromAlterDatabase(newStatements);

    if (originalName == null) {
      return newName;
    }
    if (newName == null) {
      return originalName;
    }
    if (!originalName.equals(newName)) {
      throw new DdlDiffException(
          "Database IDs differ in old and new DDL ALTER DATABASE statements");
    }
    return newName;
  }

  private static String getDatabaseNameFromAlterDatabase(List<ASTddl_statement> statements)
      throws DdlDiffException {
    Set<String> names =
        statements.stream()
            .filter(s -> s.jjtGetChild(0) instanceof ASTalter_database_statement)
            .map(s -> ((ASTalter_database_statement) s.jjtGetChild(0)).getDbName())
            .collect(Collectors.toSet());
    if (names.size() > 1) {
      throw new DdlDiffException(
          "Multiple database IDs defined in ALTER DATABASE statements in DDL");
    } else if (names.size() == 0) {
      return null;
    } else {
      return names.iterator().next();
    }
  }

  @VisibleForTesting
  static List<ASTddl_statement> parseDDL(String original) throws DdlDiffException {
    // Remove "--" comments and split by ";"
    String[] statements = original.replaceAll("--.*(\n|$)", "").split(";");
    ArrayList<ASTddl_statement> ddlStatements = new ArrayList<>(statements.length);

    for (String statement : statements) {
      statement = statement.trim();
      if (statement.isEmpty()) {
        continue;
      }
      try {
        ASTddl_statement ddlStatement = DdlParser.parseDdlStatement(statement);
        int statementType = ddlStatement.jjtGetChild(0).getId();

        switch (statementType) {
          case (DdlParserTreeConstants.JJTALTER_TABLE_STATEMENT):
            {
              ASTalter_table_statement alterTableStatement =
                  (ASTalter_table_statement) ddlStatement.jjtGetChild(0);
              // child 0 = table name
              // child 1 = alter statement. Only ASTforeign_key is supported
              if (!(alterTableStatement.jjtGetChild(1) instanceof ASTforeign_key)
                  && !(alterTableStatement.jjtGetChild(1) instanceof ASTcheck_constraint)
                  && !(alterTableStatement.jjtGetChild(1) instanceof ASTadd_row_deletion_policy)) {
                throw new IllegalArgumentException(
                    "Unsupported statement:\n"
                        + statement
                        + "\n"
                        + "Can only create diffs from 'CREATE TABLE, CREATE INDEX and 'ALTER TABLE"
                        + " table_name ADD [constraint|row deletion policy]' DDL statements");
              }
              if (alterTableStatement.jjtGetChild(1) instanceof ASTforeign_key
                  && ((ASTforeign_key) alterTableStatement.jjtGetChild(1))
                      .getName()
                      .equals(ASTcreate_table_statement.ANONYMOUS_NAME)) {
                throw new IllegalArgumentException(
                    "Unsupported statement:\n"
                        + statement
                        + "\nCan not create diffs when anonymous constraints are used.");
              }
              if (alterTableStatement.jjtGetChild(1) instanceof ASTcheck_constraint
                  && ((ASTcheck_constraint) alterTableStatement.jjtGetChild(1))
                      .getName()
                      .equals(ASTcreate_table_statement.ANONYMOUS_NAME)) {
                throw new IllegalArgumentException(
                    "Unsupported statement:\n"
                        + statement
                        + "\nCan not create diffs when anonymous constraints are used.");
              }
            }
            break;
          case DdlParserTreeConstants.JJTCREATE_TABLE_STATEMENT:
            if (((ASTcreate_table_statement) ddlStatement.jjtGetChild(0))
                .getConstraints()
                .containsKey(ASTcreate_table_statement.ANONYMOUS_NAME)) {
              throw new IllegalArgumentException(
                  "Unsupported statement:\n"
                      + statement
                      + "\nCan not create diffs when anonymous constraints are used.");
            }
            break;
          case DdlParserTreeConstants.JJTCREATE_INDEX_STATEMENT:
          case DdlParserTreeConstants.JJTALTER_DATABASE_STATEMENT:
          case DdlParserTreeConstants.JJTCREATE_CHANGE_STREAM_STATEMENT:
            // no-op
            break;
          default:
            throw new IllegalArgumentException(
                "Unsupported statement:\n"
                    + statement
                    + "\nCan only create diffs from 'CREATE TABLE, CREATE INDEX, and "
                    + "'ALTER TABLE table_name ADD CONSTRAINT' DDL statements");
        }
        ddlStatements.add(ddlStatement);
      } catch (ParseException e) {
        throw new DdlDiffException(
            String.format(
                "Unable to parse statement:\n'%s'\nFailure: %s", statement, e.getMessage()),
            e);
      }
    }
    return ddlStatements;
  }

  public static void main(String[] args) {

    DdlDiffOptions options = DdlDiffOptions.parseCommandLine(args);
    ;
    try {
      List<String> alterStatements =
          DdlDiff.build(
                  new String(Files.readAllBytes(options.originalDdlPath()), UTF_8),
                  new String(Files.readAllBytes(options.newDdlPath()), UTF_8))
              .generateDifferenceStatements(options.args());

      StringBuilder output = new StringBuilder();
      for (String statement : alterStatements) {
        output.append(statement);
        output.append(";\n\n");
      }

      Files.write(options.outputDdlPath(), output.toString().getBytes(UTF_8));

      System.exit(0);
    } catch (IOException e) {
      System.err.println("Cannot read DDL file: " + e);
    } catch (DdlDiffException e) {
      e.printStackTrace();
    }
  }
}

/**
 * Wrapper class for Check and Foreign Key constraints to include the table name for when they are
 * separated from their create table/alter table statements in
 * separateTablesIndexesConstraintsTtls().
 */
@AutoValue
abstract class ConstraintWrapper {

  static ConstraintWrapper create(String tableName, SimpleNode constraint) {
    if (!(constraint instanceof ASTforeign_key) && !(constraint instanceof ASTcheck_constraint)) {
      throw new IllegalArgumentException("not a valid constraint type : " + constraint.toString());
    }
    return new AutoValue_ConstraintWrapper(tableName, constraint);
  }

  abstract String tableName();

  abstract SimpleNode constraint();

  String getName() {
    if (constraint() instanceof ASTcheck_constraint) {
      return ((ASTcheck_constraint) constraint()).getName();
    }
    if (constraint() instanceof ASTforeign_key) {
      return ((ASTforeign_key) constraint()).getName();
    }
    throw new IllegalArgumentException("not a valid constraint type : " + constraint().toString());
  }
}

/**
 * Separarates the different DDL creation statements into separate maps.
 *
 * <p>Constraints which were created inline with their table are separated into a map with any other
 * ALTER statements which adds constraints.
 *
 * <p>This allows the diff tool to handle these objects which are created inline with the table in
 * the same way as if they were created separately with ALTER statements.
 */
@AutoValue
abstract class DatbaseDefinition {
  static DatbaseDefinition create(List<ASTddl_statement> statements) {
    // Use LinkedHashMap to preserve creation order in original DDL.
    LinkedHashMap<String, ASTcreate_table_statement> tablesInCreationOrder = new LinkedHashMap<>();
    LinkedHashMap<String, ASTcreate_index_statement> indexes = new LinkedHashMap<>();
    LinkedHashMap<String, ConstraintWrapper> constraints = new LinkedHashMap<>();
    LinkedHashMap<String, ASTrow_deletion_policy_clause> ttls = new LinkedHashMap<>();
    LinkedHashMap<String, ASTcreate_change_stream_statement> changeStreams = new LinkedHashMap<>();
    LinkedHashMap<String, String> alterDatabaseOptions = new LinkedHashMap<>();

    for (ASTddl_statement ddlStatement : statements) {
      final SimpleNode statement = (SimpleNode) ddlStatement.jjtGetChild(0);

      switch (statement.getId()) {
        case DdlParserTreeConstants.JJTCREATE_TABLE_STATEMENT:
          {
            ASTcreate_table_statement createTable = (ASTcreate_table_statement) statement;
            // Remove embedded constraint statements from the CreateTable node
            // as they are taken into account via `constraints`
            tablesInCreationOrder.put(createTable.getTableName(), createTable.clearConstraints());

            // convert embedded constraint statements into wrapper object with table name
            // use a single map for all foreign keys, constraints and row deletion polcies whether
            // created in table or externally
            createTable.getConstraints().values().stream()
                .map(c -> ConstraintWrapper.create(createTable.getTableName(), c))
                .forEach(c -> constraints.put(c.getName(), c));

            // Move embedded Row Deletion Policies
            final Optional<ASTrow_deletion_policy_clause> rowDeletionPolicyClause =
                createTable.getRowDeletionPolicyClause();
            rowDeletionPolicyClause.ifPresent(rdp -> ttls.put(createTable.getTableName(), rdp));
          }
          break;
        case DdlParserTreeConstants.JJTCREATE_INDEX_STATEMENT:
          indexes.put(
              ((ASTcreate_index_statement) statement).getIndexName(),
              (ASTcreate_index_statement) statement);
          break;
        case DdlParserTreeConstants.JJTALTER_TABLE_STATEMENT:
          {
            // Alter table can be adding Index, Constraint or Row Deletion Policy
            ASTalter_table_statement alterTable = (ASTalter_table_statement) statement;
            final String tableName = alterTable.jjtGetChild(0).toString();

            if (alterTable.jjtGetChild(1) instanceof ASTforeign_key
                || alterTable.jjtGetChild(1) instanceof ASTcheck_constraint) {
              ConstraintWrapper constraint =
                  ConstraintWrapper.create(tableName, (SimpleNode) alterTable.jjtGetChild(1));
              constraints.put(constraint.getName(), constraint);

            } else if (statement.jjtGetChild(1) instanceof ASTadd_row_deletion_policy) {
              ttls.put(
                  tableName,
                  (ASTrow_deletion_policy_clause) alterTable.jjtGetChild(1).jjtGetChild(0));
            } else {
              // other ALTER statements are not supported.
              throw new IllegalArgumentException(
                  "Unsupported ALTER TABLE statement: "
                      + ASTTreeUtils.tokensToString(ddlStatement));
            }
          }
          break;
        case DdlParserTreeConstants.JJTALTER_DATABASE_STATEMENT:
          alterDatabaseOptions.putAll(
              ((ASTalter_database_statement) statement).getOptionsClause().getKeyValueMap());
          break;
        case DdlParserTreeConstants.JJTCREATE_CHANGE_STREAM_STATEMENT:
          changeStreams.put(
              ((ASTcreate_change_stream_statement) statement).getName(),
              (ASTcreate_change_stream_statement) statement);
          break;
        default:
          throw new IllegalArgumentException(
              "Unsupported statement: " + ASTTreeUtils.tokensToString(ddlStatement));
      }
    }
    return new AutoValue_DatbaseDefinition(
        ImmutableMap.copyOf(tablesInCreationOrder),
        ImmutableMap.copyOf(indexes),
        ImmutableMap.copyOf(constraints),
        ImmutableMap.copyOf(ttls),
        ImmutableMap.copyOf(changeStreams),
        ImmutableMap.copyOf(alterDatabaseOptions));
  }

  abstract Map<String, ASTcreate_table_statement> tablesInCreationOrder();

  abstract Map<String, ASTcreate_index_statement> indexes();

  abstract Map<String, ConstraintWrapper> constraints();

  abstract Map<String, ASTrow_deletion_policy_clause> ttls();

  abstract Map<String, ASTcreate_change_stream_statement> changeStreams();

  abstract Map<String, String> alterDatabaseOptions();
}
