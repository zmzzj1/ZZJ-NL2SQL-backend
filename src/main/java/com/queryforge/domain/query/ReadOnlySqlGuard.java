package com.queryforge.domain.query;

import com.queryforge.shared.error.ApiException;
import com.queryforge.shared.error.ErrorCode;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.Statements;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.util.TablesNamesFinder;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * 只读 SQL 的结构校验。解析器不是唯一防线：数据库账号仅有 SELECT 权限，
 * JdbcTemplate 连接为 READ ONLY，且执行侧还有超时与行数上限。
 */
@Component
public class ReadOnlySqlGuard {

    private static final Pattern INTO_FILE = Pattern.compile("\\bINTO\\s+(OUTFILE|DUMPFILE)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern FOR_UPDATE = Pattern.compile("\\bFOR\\s+UPDATE\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern SHARE_LOCK = Pattern.compile("\\bLOCK\\s+IN\\s+SHARE\\s+MODE\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern DANGEROUS_FUNCTION = Pattern.compile(
            "\\b(SLEEP|BENCHMARK|LOAD_FILE|GET_LOCK|RELEASE_LOCK|MASTER_POS_WAIT|FILE_READ)\\s*\\(", Pattern.CASE_INSENSITIVE);
    private static final Pattern SYSTEM_SCHEMA = Pattern.compile(
            "\\b(information_schema|performance_schema|mysql|sys)\\s*\\.", Pattern.CASE_INSENSITIVE);

    /**
     * @param allowedSchema 当前选中连接对应的数据库名
     * @return 去掉尾部空白与分号后的可执行 SQL
     * @throws ApiException 不符合只读约束时抛出 SQL_NOT_ALLOWED
     */
    public String validate(String rawSql, String allowedSchema) {
        if (rawSql == null || rawSql.isBlank()) {
            throw reject("SQL 不能为空");
        }
        String sql = rawSql.strip();
        while (sql.endsWith(";")) {
            sql = sql.substring(0, sql.length() - 1).strip();
        }
        if (sql.isEmpty()) {
            throw reject("SQL 不能为空");
        }

        Statements statements;
        try {
            statements = CCJSqlParserUtil.parseStatements(sql);
        } catch (JSQLParserException ex) {
            throw reject("SQL 语法无法解析或不受支持");
        }
        if (statements.getStatements().size() != 1) {
            throw reject("只允许执行单条 SQL");
        }
        Statement statement = statements.getStatements().get(0);
        if (!(statement instanceof Select)) {
            throw reject("只允许只读 SELECT 查询");
        }

        String structuralView = stripLiteralsAndComments(sql).toLowerCase(Locale.ROOT);
        if (INTO_FILE.matcher(structuralView).find()
                || FOR_UPDATE.matcher(structuralView).find()
                || SHARE_LOCK.matcher(structuralView).find()) {
            throw reject("不允许写文件或加锁的查询");
        }
        if (DANGEROUS_FUNCTION.matcher(structuralView).find()) {
            throw reject("不允许调用延时、文件或锁相关函数");
        }
        if (SYSTEM_SCHEMA.matcher(structuralView).find()) {
            throw reject("不允许访问系统库");
        }

        assertOnlyAllowedSchema(sql, allowedSchema);
        return sql;
    }

    private void assertOnlyAllowedSchema(String sql, String allowedSchema) {
        Set<String> tables;
        try {
            tables = TablesNamesFinder.findTables(sql);
        } catch (JSQLParserException ex) {
            throw reject("SQL 语法无法解析或不受支持");
        }
        for (String qualified : tables) {
            String normalized = qualified.replace("`", "").replace("\"", "").strip();
            String[] parts = normalized.split("\\.");
            if (parts.length >= 2) {
                String schema = parts[parts.length - 2];
                if (!allowedSchema.equalsIgnoreCase(schema)) {
                    throw reject("只允许查询当前连接的库 " + allowedSchema);
                }
            }
        }
    }

    /**
     * 去掉字符串字面量与注释，避免把字面量中的关键字误判为危险语法。
     */
    private static String stripLiteralsAndComments(String sql) {
        String withoutBlockComments = sql.replaceAll("(?s)/\\*.*?\\*/", " ");
        String withoutLineComments = withoutBlockComments
                .replaceAll("--[^\\n]*", " ")
                .replaceAll("#[^\\n]*", " ");
        return withoutLineComments
                .replaceAll("'(?:''|[^'])*'", "''")
                .replaceAll("\"(?:\"\"|[^\"])*\"", "\"\"");
    }

    private static ApiException reject(String message) {
        return new ApiException(ErrorCode.SQL_NOT_ALLOWED, HttpStatus.BAD_REQUEST, message);
    }
}
