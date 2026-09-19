package com.moyi.identity.infra.database

import org.hibernate.type.descriptor.WrapperOptions
import org.hibernate.usertype.UserType
import java.io.Serializable
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Types

/**
 * Binds a Kotlin [String] to a PostgreSQL `citext` column.
 *
 * **Why this has to exist.** `citext` is case-insensitive, which is the whole
 * reason doc 07 chose it for `email`. But Hibernate maps a `String` to
 * `varchar`, and the JDBC driver therefore tells PostgreSQL that the
 * parameter in `WHERE email = ?` *is* a varchar. PostgreSQL has no
 * `citext = varchar` operator, so it resolves the comparison through the
 * implicit `citext -> text` cast and compares two ordinary strings —
 * case-sensitively.
 *
 * Nothing fails. The column is still `citext`, the unique index is still
 * case-insensitive, `ddl-auto: validate` is still happy, the insert still
 * works. Only the lookup is wrong, and it is wrong by returning *nothing*,
 * which reads exactly like "no such user". `UserPersistenceTest` found this
 * on the first query ever written against the column; a login path would
 * have found it as "my password stopped working when I capitalised my
 * email".
 *
 * **The fix** is to stop claiming a type for the parameter. Binding through
 * `setObject(…, Types.OTHER)` makes the driver send it without an OID, and
 * PostgreSQL then infers the type from the column it is being compared to —
 * `citext` — and picks the case-insensitive operator.
 *
 * **Why not the alternatives.** `stringtype=unspecified` on the JDBC URL does
 * the same thing for *every* string parameter in the application, which is a
 * large blast radius for one column. A native query with an explicit
 * `CAST(:email AS citext)` fixes one query and leaves the next one to
 * rediscover this — and the failure is silent, so it would not be
 * rediscovered quickly.
 *
 * Lives in `identity` because `identity` is the only module with such a
 * column today. It moves to `common` the moment a second one appears, not
 * before.
 */
internal class CitextType : UserType<String> {
    override fun getSqlType(): Int = Types.OTHER

    override fun returnedClass(): Class<String> = String::class.java

    /**
     * Hibernate uses this for dirty checking, so it must answer "does this
     * need an UPDATE", not "would the database consider these equal".
     * `Ada@example.com` and `ada@example.com` are the same row to `citext`
     * but a real change to the stored value, and we store what was typed.
     */
    override fun equals(
        x: String?,
        y: String?,
    ): Boolean = x == y

    override fun hashCode(x: String?): Int = x?.hashCode() ?: 0

    // The `WrapperOptions` overloads, not the `SharedSessionContractImplementor`
    // ones: Hibernate 7 deprecated the latter, and overriding a deprecated
    // method is how a library's next major version removes your code.
    override fun nullSafeGet(
        rs: ResultSet,
        position: Int,
        options: WrapperOptions,
    ): String? = rs.getString(position)

    override fun nullSafeSet(
        st: PreparedStatement,
        value: String?,
        index: Int,
        options: WrapperOptions,
    ) {
        if (value == null) {
            st.setNull(index, Types.OTHER)
        } else {
            // The load-bearing line. `setString` would send an OID for
            // varchar; `setObject` with OTHER sends the value untyped and
            // lets PostgreSQL infer `citext` from context.
            st.setObject(index, value, Types.OTHER)
        }
    }

    override fun deepCopy(value: String?): String? = value

    override fun isMutable(): Boolean = false

    override fun disassemble(value: String?): Serializable? = value

    override fun assemble(
        cached: Serializable?,
        owner: Any?,
    ): String? = cached as String?
}
