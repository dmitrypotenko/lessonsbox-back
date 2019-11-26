package com.dpotenko.kirillweb.service

import com.dpotenko.kirillweb.Tables
import com.dpotenko.kirillweb.dto.VariantDto
import com.dpotenko.kirillweb.tables.pojos.Variant
import org.jooq.DSLContext
import org.springframework.stereotype.Component

@Component
class VariantService(val dslContext: DSLContext) {
    fun saveVariant(dto: VariantDto,
                    questionId: Long): Long {
        val record = dslContext.newRecord(Tables.VARIANT, Variant(dto.id, dto.isTicked, dto.isWrong, dto.isRight, dto.variant, null, questionId))
        if (dto.id == null) {
            record.insert()
        } else {
            record.update()
        }

        return record.id
    }

}