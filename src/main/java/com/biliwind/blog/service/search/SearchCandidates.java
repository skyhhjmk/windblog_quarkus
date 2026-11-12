package com.biliwind.blog.service.search;

import java.util.List;

/** One backend's ordered rows plus the total number of matches it saw. */
record SearchCandidates(List<SearchHit> hits, long total) {
}
