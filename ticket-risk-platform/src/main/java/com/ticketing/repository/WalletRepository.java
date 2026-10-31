package com.ticketing.repository;

/**
 * Wallet balance CAS on {@code tb_wallet_account}.
 */
public interface WalletRepository {

    /** Debit when {@code available_balance >= amount}. Returns true iff one row updated. */
    boolean tryDebit(long userId, Object amount);

    /** Credit by user id. Returns true iff one row updated (wallet exists). */
    boolean tryCredit(long userId, Object amount);
}
