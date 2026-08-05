package com.maxrave.domain.data.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.maxrave.domain.extension.now
import kotlinx.datetime.LocalDateTime

@Entity(tableName = "notification")
data class NotificationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val channelId: String,
    val thumbnail: String? = null,
    val name: String,
    val single: List<Map<String, String>> = listOf(),
    val album: List<Map<String, String>> = listOf(),
    val time: LocalDateTime = now(),
    // @ColumnInfo(defaultValue) is REQUIRED: Room AutoMigration adds this NOT NULL column and
    // needs a SQL default to backfill existing rows (the Kotlin default alone is not enough).
    @ColumnInfo(defaultValue = "artist")
    val type: String = TYPE_ARTIST,
    // Currently unused by the app (the blog RSS feature was removed); kept to avoid
    // changing the Room schema / auto-migration surface.
    val link: String? = null,
    val description: String? = null,
) {
    companion object {
        const val TYPE_ARTIST = "artist"
    }
}