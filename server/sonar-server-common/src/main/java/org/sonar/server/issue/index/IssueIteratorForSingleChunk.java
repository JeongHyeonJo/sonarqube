/*
 * SonarQube
 * Copyright (C) 2009-2018 SonarSource SA
 * mailto:info AT sonarsource DOT com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package org.sonar.server.issue.index;

import com.google.common.base.CharMatcher;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterators;
import com.google.common.collect.Maps;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import javax.annotation.CheckForNull;
import javax.annotation.Nullable;
import org.apache.commons.lang.StringUtils;
import org.sonar.api.resources.Scopes;
import org.sonar.api.rules.RuleType;
import org.sonar.db.DatabaseUtils;
import org.sonar.db.DbClient;
import org.sonar.db.DbSession;
import org.sonar.db.ResultSetIterator;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Arrays.asList;
import static java.util.stream.Collectors.toList;
import static org.sonar.api.utils.DateUtils.longToDate;
import static org.sonar.db.DatabaseUtils.getLong;
import static org.sonar.server.issue.ws.SearchAction.SANS_TOP_25_INSECURE_INTERACTION;
import static org.sonar.server.issue.ws.SearchAction.SANS_TOP_25_POROUS_DEFENSES;
import static org.sonar.server.issue.ws.SearchAction.SANS_TOP_25_RISKY_RESOURCE;
import static org.sonar.server.issue.ws.SearchAction.UNKNOWN_STANDARD;

/**
 * Scrolls over table ISSUES and reads documents to populate
 * the issues index
 */
class IssueIteratorForSingleChunk implements IssueIterator {

  private static final String[] FIELDS = {
    // column 1
    "i.kee",
    "i.assignee",
    "i.line",
    "i.resolution",
    "i.severity",
    "i.status",
    "i.effort",
    "i.author_login",
    "i.issue_close_date",
    "i.issue_creation_date",

    // column 11
    "i.issue_update_date",
    "r.id",
    "r.language",
    "c.uuid",
    "c.module_uuid_path",
    "c.path",
    "c.scope",
    "c.organization_uuid",
    "c.project_uuid",
    "c.main_branch_project_uuid",

    // column 21
    "i.tags",
    "i.issue_type",
    "r.security_standards"
  };

  private static final String SQL_ALL = "select " + StringUtils.join(FIELDS, ",") + " from issues i " +
    "inner join rules r on r.id = i.rule_id " +
    "inner join projects c on c.uuid = i.component_uuid ";

  private static final String PROJECT_FILTER = " and c.project_uuid = ? and i.project_uuid = ? ";
  private static final String ISSUE_KEY_FILTER_PREFIX = " and i.kee in (";
  private static final String ISSUE_KEY_FILTER_SUFFIX = ")";

  static final Splitter TAGS_SPLITTER = Splitter.on(',').trimResults().omitEmptyStrings();
  static final Splitter SECURITY_STANDARDS_SPLITTER = TAGS_SPLITTER;
  static final Splitter MODULE_PATH_SPLITTER = Splitter.on('.').trimResults().omitEmptyStrings();
  private static final String OWASP_TOP10_PREFIX = "owaspTop10:";
  private static final String CWE_PREFIX = "cwe:";

  // See https://www.sans.org/top25-software-errors
  private static final Set<String> INSECURE_CWE = new HashSet<>(asList("89", "78", "79", "434", "352", "601"));
  private static final Set<String> RISKY_CWE = new HashSet<>(asList("120", "22", "494", "829", "676", "131", "134", "190"));
  private static final Set<String> POROUS_CWE = new HashSet<>(asList("306", "862", "798", "311", "807", "250", "863", "732", "327", "307", "759"));
  private static final Map<String, Set<String>> SANS_TOP_25_CWE_MAPPING = ImmutableMap.of(
    SANS_TOP_25_INSECURE_INTERACTION, INSECURE_CWE,
    SANS_TOP_25_RISKY_RESOURCE, RISKY_CWE,
    SANS_TOP_25_POROUS_DEFENSES, POROUS_CWE);

  private final DbSession session;

  @CheckForNull
  private final String projectUuid;

  @CheckForNull
  private final Collection<String> issueKeys;

  private final PreparedStatement stmt;
  private final ResultSetIterator<IssueDoc> iterator;

  IssueIteratorForSingleChunk(DbClient dbClient, @Nullable String projectUuid, @Nullable Collection<String> issueKeys) {
    checkArgument(issueKeys == null || issueKeys.size() <= DatabaseUtils.PARTITION_SIZE_FOR_ORACLE,
      "Cannot search for more than " + DatabaseUtils.PARTITION_SIZE_FOR_ORACLE + " issue keys at once. Please provide the keys in smaller chunks.");
    this.projectUuid = projectUuid;
    this.issueKeys = issueKeys;
    this.session = dbClient.openSession(false);

    try {
      String sql = createSql();
      stmt = dbClient.getMyBatis().newScrollingSelectStatement(session, sql);
      iterator = createIterator();
    } catch (Exception e) {
      session.close();
      throw new IllegalStateException("Fail to prepare SQL request to select all issues", e);
    }
  }

  private IssueIteratorInternal createIterator() {
    try {
      setParameters(stmt);
      return new IssueIteratorInternal(stmt);
    } catch (SQLException e) {
      DatabaseUtils.closeQuietly(stmt);
      throw new IllegalStateException("Fail to prepare SQL request to select all issues", e);
    }
  }

  @Override
  public boolean hasNext() {
    return iterator.hasNext();
  }

  @Override
  public IssueDoc next() {
    return iterator.next();
  }

  private String createSql() {
    String sql = SQL_ALL;
    sql += projectUuid == null ? "" : PROJECT_FILTER;
    if (issueKeys != null && !issueKeys.isEmpty()) {
      sql += ISSUE_KEY_FILTER_PREFIX;
      sql += IntStream.range(0, issueKeys.size()).mapToObj(i -> "?").collect(Collectors.joining(","));
      sql += ISSUE_KEY_FILTER_SUFFIX;
    }
    return sql;
  }

  private void setParameters(PreparedStatement stmt) throws SQLException {
    int index = 1;
    if (projectUuid != null) {
      stmt.setString(index, projectUuid);
      index++;
      stmt.setString(index, projectUuid);
      index++;
    }
    if (issueKeys != null) {
      for (String key : issueKeys) {
        stmt.setString(index, key);
        index++;
      }
    }
  }

  @Override
  public void close() {
    try {
      iterator.close();
    } finally {
      DatabaseUtils.closeQuietly(stmt);
      session.close();
    }
  }

  private static final class IssueIteratorInternal extends ResultSetIterator<IssueDoc> {

    public IssueIteratorInternal(PreparedStatement stmt) throws SQLException {
      super(stmt);
    }

    @Override
    protected IssueDoc read(ResultSet rs) throws SQLException {
      IssueDoc doc = new IssueDoc(Maps.newHashMapWithExpectedSize(30));

      String key = rs.getString(1);

      // all the fields must be present, even if value is null
      doc.setKey(key);
      doc.setAssigneeUuid(rs.getString(2));
      doc.setLine(DatabaseUtils.getInt(rs, 3));
      doc.setResolution(rs.getString(4));
      doc.setSeverity(rs.getString(5));
      doc.setStatus(rs.getString(6));
      doc.setEffort(getLong(rs, 7));
      doc.setAuthorLogin(rs.getString(8));
      doc.setFuncCloseDate(longToDate(getLong(rs, 9)));
      doc.setFuncCreationDate(longToDate(getLong(rs, 10)));
      doc.setFuncUpdateDate(longToDate(getLong(rs, 11)));
      Integer ruleId = rs.getInt(12);
      doc.setRuleId(ruleId);
      doc.setLanguage(rs.getString(13));
      doc.setComponentUuid(rs.getString(14));
      String moduleUuidPath = rs.getString(15);
      doc.setModuleUuid(extractModule(moduleUuidPath));
      doc.setModuleUuidPath(moduleUuidPath);
      String scope = rs.getString(17);
      String filePath = extractFilePath(rs.getString(16), scope);
      doc.setFilePath(filePath);
      doc.setDirectoryPath(extractDirPath(doc.filePath(), scope));
      doc.setOrganizationUuid(rs.getString(18));
      String branchUuid = rs.getString(19);
      String mainBranchProjectUuid = DatabaseUtils.getString(rs, 20);
      doc.setBranchUuid(branchUuid);
      if (mainBranchProjectUuid == null) {
        doc.setProjectUuid(branchUuid);
        doc.setIsMainBranch(true);
      } else {
        doc.setProjectUuid(mainBranchProjectUuid);
        doc.setIsMainBranch(false);
      }
      String tags = rs.getString(21);
      doc.setTags(IssueIteratorForSingleChunk.TAGS_SPLITTER.splitToList(tags == null ? "" : tags));
      doc.setType(RuleType.valueOf(rs.getInt(22)));
      String securityStandards = rs.getString(23);

      List<String> standards = IssueIteratorForSingleChunk.SECURITY_STANDARDS_SPLITTER.splitToList(securityStandards == null ? "" : securityStandards);
      List<String> owaspTop10 = standards.stream().filter(s -> s.startsWith(OWASP_TOP10_PREFIX)).map(s -> s.substring(OWASP_TOP10_PREFIX.length())).collect(toList());
      doc.setOwaspTop10(owaspTop10.isEmpty() ? Collections.singletonList(UNKNOWN_STANDARD) : owaspTop10);
      List<String> cwe = standards.stream().filter(s -> s.startsWith(CWE_PREFIX)).map(s -> s.substring(CWE_PREFIX.length())).collect(toList());
      doc.setCwe(cwe.isEmpty() ? Collections.singletonList(UNKNOWN_STANDARD) : cwe);
      doc.setSansTop25(getSansTop25(cwe));
      return doc;
    }

    private static List<String> getSansTop25(List<String> cwe) {
      return SANS_TOP_25_CWE_MAPPING
        .keySet()
        .stream()
        .filter(k -> cwe.stream().anyMatch(SANS_TOP_25_CWE_MAPPING.get(k)::contains))
        .collect(toList());
    }

    @CheckForNull
    private static String extractDirPath(@Nullable String filePath, String scope) {
      if (filePath != null) {
        if (Scopes.DIRECTORY.equals(scope)) {
          return filePath;
        }
        int lastSlashIndex = CharMatcher.anyOf("/").lastIndexIn(filePath);
        if (lastSlashIndex > 0) {
          return filePath.substring(0, lastSlashIndex);
        }
        return "/";
      }
      return null;
    }

    @CheckForNull
    private static String extractFilePath(@Nullable String filePath, String scope) {
      // On modules, the path contains the relative path of the module starting from its parent, and in E/S we're only interested in the
      // path
      // of files and directories.
      // That's why the file path should be null on modules and projects.
      if (filePath != null && !Scopes.PROJECT.equals(scope)) {
        return filePath;
      }
      return null;
    }

    private static String extractModule(String moduleUuidPath) {
      return Iterators.getLast(IssueIteratorForSingleChunk.MODULE_PATH_SPLITTER.split(moduleUuidPath).iterator());
    }
  }
}