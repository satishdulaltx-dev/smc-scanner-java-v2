package com.smcscanner.news;

/** One ticker-specific, timestamped sentiment observation from a published article. */
public record HistoricalNewsArticle(long publishedEpochMs, String sentiment) { }
