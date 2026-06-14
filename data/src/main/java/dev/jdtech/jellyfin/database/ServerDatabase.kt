package dev.jdtech.jellyfin.database

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.DeleteTable
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.AutoMigrationSpec
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import dev.jdtech.jellyfin.models.DownloadQueueEntryDto
import dev.jdtech.jellyfin.models.FindroidEpisodeDto
import dev.jdtech.jellyfin.models.FindroidMediaStreamDto
import dev.jdtech.jellyfin.models.FindroidMovieDto
import dev.jdtech.jellyfin.models.FindroidSeasonDto
import dev.jdtech.jellyfin.models.FindroidSegmentDto
import dev.jdtech.jellyfin.models.FindroidShowDto
import dev.jdtech.jellyfin.models.FindroidSourceDto
import dev.jdtech.jellyfin.models.FindroidTrickplayInfoDto
import dev.jdtech.jellyfin.models.FindroidUserDataDto
import dev.jdtech.jellyfin.models.OfflineAssetDto
import dev.jdtech.jellyfin.models.OfflineItemSnapshotDto
import dev.jdtech.jellyfin.models.OfflinePackageDto
import dev.jdtech.jellyfin.models.Server
import dev.jdtech.jellyfin.models.ServerAddress
import dev.jdtech.jellyfin.models.User

@Database(
    entities =
        [
            Server::class,
            ServerAddress::class,
            User::class,
            FindroidMovieDto::class,
            FindroidShowDto::class,
            FindroidSeasonDto::class,
            FindroidEpisodeDto::class,
            FindroidSourceDto::class,
            FindroidMediaStreamDto::class,
            FindroidUserDataDto::class,
            FindroidTrickplayInfoDto::class,
            FindroidSegmentDto::class,
            OfflinePackageDto::class,
            OfflineAssetDto::class,
            OfflineItemSnapshotDto::class,
            DownloadQueueEntryDto::class,
        ],
    version = 11,
    autoMigrations =
        [
            AutoMigration(from = 2, to = 3),
            AutoMigration(from = 3, to = 4),
            AutoMigration(from = 4, to = 5, spec = ServerDatabase.TrickplayMigration::class),
            AutoMigration(from = 5, to = 6, spec = ServerDatabase.IntrosMigration::class),
            AutoMigration(from = 7, to = 8),
            AutoMigration(from = 9, to = 10),
        ],
)
@TypeConverters(Converters::class)
abstract class ServerDatabase : RoomDatabase() {
    abstract fun getServerDatabaseDao(): ServerDatabaseDao

    @DeleteTable(tableName = "trickPlayManifests") class TrickplayMigration : AutoMigrationSpec

    @DeleteTable(tableName = "intros") class IntrosMigration : AutoMigrationSpec
}

val MIGRATION_6_7 =
    object : Migration(startVersion = 6, endVersion = 7) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("DROP TABLE segments")
            db.execSQL(
                "CREATE TABLE segments (`itemId` TEXT NOT NULL, `type` TEXT NOT NULL, `startTicks` INTEGER NOT NULL, `endTicks` INTEGER NOT NULL, PRIMARY KEY(`itemId`, `type`), FOREIGN KEY(`itemId`) REFERENCES `episodes`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )"
            )
        }
    }

val MIGRATION_10_11 =
    object : Migration(startVersion = 10, endVersion = 11) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `downloadQueue` (
                    `entryId` TEXT NOT NULL,
                    `packageId` TEXT NOT NULL,
                    `serverId` TEXT NOT NULL,
                    `itemId` TEXT NOT NULL,
                    `seriesId` TEXT,
                    `seasonId` TEXT,
                    `displayTitle` TEXT NOT NULL,
                    `seriesTitle` TEXT,
                    `seasonIndex` INTEGER,
                    `episodeIndex` INTEGER,
                    `status` TEXT NOT NULL,
                    `attempt` INTEGER NOT NULL,
                    `maxAttempts` INTEGER NOT NULL,
                    `nextAttemptAtMillis` INTEGER NOT NULL,
                    `failureReason` TEXT,
                    `failureMessage` TEXT,
                    `enqueuedAtMillis` INTEGER NOT NULL,
                    `updatedAtMillis` INTEGER NOT NULL,
                    PRIMARY KEY(`entryId`)
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_downloadQueue_status` ON `downloadQueue` (`status`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_downloadQueue_serverId` ON `downloadQueue` (`serverId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_downloadQueue_itemId` ON `downloadQueue` (`itemId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_downloadQueue_seriesId` ON `downloadQueue` (`seriesId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_downloadQueue_nextAttemptAtMillis` ON `downloadQueue` (`nextAttemptAtMillis`)")
        }
    }
