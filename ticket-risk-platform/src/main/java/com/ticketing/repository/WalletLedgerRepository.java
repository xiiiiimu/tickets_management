package com.ticketing.repository;

/**
 * Append-only wallet ledger on {@code tb_wallet_ledger}.
 */
public interface WalletLedgerRepository {

    void insert(String id, long userId, String orderId, String transactionId, String type, Object amount);
}
