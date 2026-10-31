package com.ticketing.repository.jdbc;

import com.ticketing.infrastructure.Db;
import com.ticketing.repository.WalletLedgerRepository;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcWalletLedgerRepository implements WalletLedgerRepository {

    private final Db db;

    public JdbcWalletLedgerRepository(Db db) {
        this.db = db;
    }

    @Override
    public void insert(String id, long userId, String orderId, String transactionId, String type, Object amount) {
        db.jdbc.update(
                "INSERT INTO tb_wallet_ledger(id,user_id,order_id,transaction_id,type,amount) VALUES(?,?,?,?,?,?)",
                id, userId, orderId, transactionId, type, amount);
    }
}
