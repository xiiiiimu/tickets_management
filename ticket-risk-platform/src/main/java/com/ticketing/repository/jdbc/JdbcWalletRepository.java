package com.ticketing.repository.jdbc;

import com.ticketing.infrastructure.Db;
import com.ticketing.repository.WalletRepository;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcWalletRepository implements WalletRepository {

    private final Db db;

    public JdbcWalletRepository(Db db) {
        this.db = db;
    }

    @Override
    public boolean tryDebit(long userId, Object amount) {
        return db.jdbc.update(
                "UPDATE tb_wallet_account SET available_balance=available_balance-?,version=version+1 WHERE user_id=? AND available_balance>=?",
                amount, userId, amount) == 1;
    }

    @Override
    public boolean tryCredit(long userId, Object amount) {
        return db.jdbc.update(
                "UPDATE tb_wallet_account SET available_balance=available_balance+?,version=version+1 WHERE user_id=?",
                amount, userId) == 1;
    }
}
