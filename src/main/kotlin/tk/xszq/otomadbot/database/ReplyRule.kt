@file:Suppress("unused", "UNUSED_PARAMETER")
package tk.xszq.otomadbot.database

import net.mamoe.mirai.event.events.GroupMessageEvent
import net.mamoe.mirai.message.data.*
import org.jetbrains.exposed.dao.IntEntity
import org.jetbrains.exposed.dao.IntEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.`java-time`.timestamp
import tk.xszq.otomadbot.*
import tk.xszq.otomadbot.api.getOCRText
import java.time.Instant

enum class ReplyRuleType(val type: Int) {
    INCLUDE(0),
    EQUAL(1),
    REGEX(2),
    ANY(3),
    ALL(4),
    PIC_INCLUDE(-1),
    PIC_ALL(-2),
    PIC_ANY(-3)
}

object ReplyRules : IntIdTable() {
    override val tableName = "reply"
    val name = varchar("name", 64)
    val rule = varchar("rule", 1024)
    val reply = varchar("reply", 1024)
    val group = long("qqgroup")
    val creator = long("creator")
    val type = integer("type")
    val createTime = timestamp("createtime")
    suspend fun getRulesBySubject(subject: Long): SizedIterable<ReplyRule> {
        return newLockedSuspendedTransaction(db = Databases.cache) { ReplyRule.find { group eq subject } }
    }
    suspend fun getRuleById(id: Int): ReplyRule? {
        return newLockedSuspendedTransaction(db = Databases.cache) { ReplyRule.findById(id) }
    }
    suspend fun insertRule(rule: String, reply: String, subject: Long, type: Int, creator: Long) {
        newLockedSuspendedTransaction(db = Databases.mysql) {
            ReplyRule.new {
                this.name = ""
                this.rule = rule
                this.reply = reply
                this.group = subject
                this.type = type
                this.creator = creator
                this.createTime = Instant.now()
            }
        }
	Databases.refreshCache()
    }
    suspend fun removeRule(id: Int): Boolean {
        return newLockedSuspendedTransaction(db = Databases.cache) {
            var result = false
            ReplyRule.findById(id)?.let{
                it.delete()
                result = true
            }
            result
        }
    }
    private suspend fun matchInclude(event: GroupMessageEvent, content: String, ruleType: ReplyRuleType =
        ReplyRuleType.INCLUDE) = newLockedSuspendedTransaction(db = Databases.cache) {
        ReplyRule.find {
            type eq ruleType.type and
                    (group eq event.group.id or (group eq -1)) and InStr(content, rule)
        }.maxByOrNull { it.createTime }
    }
    private suspend fun matchEqual(event: GroupMessageEvent, content: String) =
        newLockedSuspendedTransaction(db = Databases.cache) {
        ReplyRule.find {
            type eq ReplyRuleType.EQUAL.type and
                    (group eq event.group.id or (group eq -1)) and (rule eq content)
        }.maxByOrNull { it.createTime }
    }
    private suspend fun matchRegex(event: GroupMessageEvent, content: String) =
        newLockedSuspendedTransaction(db = Databases.cache) {
        ReplyRule.find {
            type eq ReplyRuleType.REGEX.type and
                    (group eq event.group.id or (group eq -1)) and
                    RegexpOpCol(stringParam(content), "rule")
        }.maxByOrNull { it.createTime }
    }
    private suspend fun matchAny(event: GroupMessageEvent, content: String,
                                 ruleType: ReplyRuleType = ReplyRuleType.ANY) =
        newLockedSuspendedTransaction(db = Databases.cache) {
        var result: ReplyRule? = null
        ReplyRule.find {
            type eq ruleType.type and (group eq event.group.id)
        }.sortedByDescending { it.createTime }.forEach { rule ->
            rule.rule.split(",").forEach ensure@{ keyword ->
                if (keyword in content) {
                    result = rule; return@ensure
                }
            }
            result?.let { return@forEach }
        }
        result
    }
    private suspend fun matchAll(event: GroupMessageEvent, content: String,
                                 ruleType: ReplyRuleType = ReplyRuleType.ALL) =
        newLockedSuspendedTransaction(db = Databases.cache) {
        var result: ReplyRule? = null
        ReplyRule.find {
            type eq ruleType.type and (group eq event.group.id or (group eq -1))
        }.sortedByDescending { it.createTime }.forEach { rule ->
            var isMatched = rule.rule.isNotBlank()
            rule.rule.split(",").forEach ensure@{ keyword ->
                if (keyword !in content) {
                    isMatched = false; return@ensure
                }
            }
            if (isMatched) {
                result = rule
                return@forEach
            }
        }
        result
    }
    private suspend fun hasImageRule(group: Long): Boolean = newLockedSuspendedTransaction(db = Databases.cache) {
        ReplyRule.find { type less 0 and (ReplyRules.group eq group) }.count() > 0L
    }
    suspend fun match(event: GroupMessageEvent): String? = event.run {
        var content = message.content.toSimple()
        var result: String? = null
        if (message.anyIsInstance<PlainText>()) {
            result ?: matchInclude(this, content)?.let { result = it.reply }
            result ?: matchEqual(this, content)?.let { result = it.reply }
            result ?: matchRegex(this, content)?.let { result = it.reply }
            result ?: matchAny(this, content)?.let { result = it.reply }
            result ?: matchAll(this, content)?.let { result = it.reply }
        }
        result ?: run {
            if (message.anyIsInstance<Image>() && hasImageRule(group.id)) {
                content = ""
                message.forEach {
                    content += if (it is Image) getOCRText(it) + " " else ""
                }
                if (!content.isEmptyChar()) {
                    result ?: matchInclude(this, content, ReplyRuleType.PIC_INCLUDE)?.let { result = it.reply }
                    result ?: matchAll(this, content, ReplyRuleType.PIC_ALL)?.let { result = it.reply }
                    result ?: matchAny(this, content, ReplyRuleType.PIC_ANY)?.let { result = it.reply }
                }
            }
        }
        return result
    }
}
class ReplyRule(id: EntityID<Int>) : IntEntity(id) {
    var name by ReplyRules.name
    var rule by ReplyRules.rule
    var reply by ReplyRules.reply
    var group by ReplyRules.group
    var creator by ReplyRules.creator
    var type by ReplyRules.type
    var createTime by ReplyRules.createTime

    companion object : IntEntityClass<ReplyRule>(ReplyRules) {
        fun getNameFromType(type: Int): String? {
            return when (type) {
                ReplyRuleType.PIC_INCLUDE.type -> "????????????"
                ReplyRuleType.PIC_ALL.type -> "????????????"
                ReplyRuleType.PIC_ANY.type -> "????????????"
                ReplyRuleType.INCLUDE.type -> "??????"
                ReplyRuleType.EQUAL.type -> "??????"
                ReplyRuleType.REGEX.type -> "??????"
                ReplyRuleType.ANY.type -> "??????"
                ReplyRuleType.ALL.type -> "??????"
                else -> null
            }
        }
        fun getTypeFromName(name: String): Int? {
            return when (name) {
                "????????????" -> ReplyRuleType.PIC_INCLUDE.type
                "????????????" -> ReplyRuleType.PIC_ALL.type
                "????????????" -> ReplyRuleType.PIC_ANY.type
                "??????" -> ReplyRuleType.INCLUDE.type
                "??????" -> ReplyRuleType.EQUAL.type
                "??????" -> ReplyRuleType.REGEX.type
                "??????" -> ReplyRuleType.ANY.type
                "??????" -> ReplyRuleType.ALL.type
                else -> null
            }
        }
    }
}
