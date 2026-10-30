import { pgTable, uuid, text, timestamp, integer, bigint, boolean, jsonb, date, uniqueIndex, index } from 'drizzle-orm/pg-core';
import { relations } from 'drizzle-orm';

export const content = pgTable(
  'content',
  {
    id: uuid('id').defaultRandom().primaryKey(),
    tpdbId: text('tpdb_id').notNull().unique(),
    contentType: text('content_type').notNull(), // 'MOVIE' | 'SERIES'
    title: text('title').notNull(),
    studio: text('studio'),
    releaseDate: date('release_date'),
    createdAt: timestamp('created_at', { withTimezone: true }).defaultNow().notNull(),
    updatedAt: timestamp('updated_at', { withTimezone: true }).defaultNow().notNull(),
  },
  (table) => ({
    tpdbIdx: uniqueIndex('content_tpdb_id_idx').on(table.tpdbId),
    titleIdx: index('content_title_idx').on(table.title),
  })
);

export const contentAliases = pgTable(
  'content_aliases',
  {
    id: uuid('id').defaultRandom().primaryKey(),
    contentId: uuid('content_id')
      .notNull()
      .references(() => content.id, { onDelete: 'cascade' }),
    aliasTitle: text('alias_title').notNull(),
  },
  (table) => ({
    aliasTitleIdx: index('content_alias_title_idx').on(table.aliasTitle),
  })
);

export const releases = pgTable(
  'releases',
  {
    id: uuid('id').defaultRandom().primaryKey(),
    contentId: uuid('content_id')
      .notNull()
      .references(() => content.id, { onDelete: 'cascade' }),
    releaseTitle: text('release_title').notNull(),
    resolution: text('resolution').default('1080p'),
    seasonNumber: integer('season_number'),
    episodeNumber: integer('episode_number'),
    fileSizeBytes: bigint('file_size_bytes', { mode: 'number' }),
    createdAt: timestamp('created_at', { withTimezone: true }).defaultNow().notNull(),
  },
  (table) => ({
    contentIdIdx: index('releases_content_id_idx').on(table.contentId),
    seasonEpIdx: index('releases_season_ep_idx').on(table.contentId, table.seasonNumber, table.episodeNumber),
  })
);

export const sources = pgTable(
  'sources',
  {
    id: uuid('id').defaultRandom().primaryKey(),
    releaseId: uuid('release_id')
      .notNull()
      .references(() => releases.id, { onDelete: 'cascade' }),
    providerId: text('provider_id').notNull(),
    streamUrl: text('stream_url').notNull(),
    isDirectStream: boolean('is_direct_stream').default(true).notNull(),
    isM3u8: boolean('is_m3u8').default(false).notNull(),
    headers: jsonb('headers').$type<Record<string, string>>().default({}),
    status: text('status').default('ACTIVE').notNull(), // 'ACTIVE' | 'DEAD' | 'UNKNOWN'
    verifiedAt: timestamp('verified_at', { withTimezone: true }).defaultNow().notNull(),
  },
  (table) => ({
    releaseIdIdx: index('sources_release_id_idx').on(table.releaseId),
    streamUrlIdx: index('sources_stream_url_idx').on(table.streamUrl),
  })
);

export const indexingJobs = pgTable(
  'indexing_jobs',
  {
    id: uuid('id').defaultRandom().primaryKey(),
    contentId: uuid('content_id').references(() => content.id, { onDelete: 'cascade' }),
    status: text('status').notNull(), // 'PENDING' | 'PROCESSING' | 'SUCCESS' | 'FAILED' | 'EMPTY'
    attempts: integer('attempts').default(1).notNull(),
    errorMessage: text('error_message'),
    startedAt: timestamp('started_at', { withTimezone: true }).defaultNow().notNull(),
    finishedAt: timestamp('finished_at', { withTimezone: true }),
  }
);

export const refreshSchedules = pgTable(
  'refresh_schedules',
  {
    id: uuid('id').defaultRandom().primaryKey(),
    contentId: uuid('content_id')
      .notNull()
      .references(() => content.id, { onDelete: 'cascade' }),
    nextRefreshAt: timestamp('next_refresh_at', { withTimezone: true }).notNull(),
    refreshIntervalSeconds: integer('refresh_interval_seconds').default(86400).notNull(),
    consecutiveEmptyScans: integer('consecutive_empty_scans').default(0).notNull(),
  },
  (table) => ({
    nextRefreshIdx: index('refresh_schedules_next_refresh_idx').on(table.nextRefreshAt),
  })
);

export const contentRelations = relations(content, ({ many }) => ({
  releases: many(releases),
  aliases: many(contentAliases),
}));

export const releasesRelations = relations(releases, ({ one, many }) => ({
  content: one(content, { fields: [releases.contentId], references: [content.id] }),
  sources: many(sources),
}));

export const sourcesRelations = relations(sources, ({ one }) => ({
  release: one(releases, { fields: [sources.releaseId], references: [releases.id] }),
}));
