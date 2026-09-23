package com.mal.ledger.events;

import com.mal.ledger.domain.Currency;

/** An account the stream opens, at a zero closing ledger. */
public record AccountSpec(String id, Currency currency) {
}
