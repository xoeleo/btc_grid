package com.btcgrid.bybit.adapter;

import com.btcgrid.common.model.AccountAssetSnapshot;
import com.btcgrid.common.model.ProductCategory;

public interface BybitAccountGateway {
    AccountAssetSnapshot walletSnapshot(
            String accountType,
            String baseCoin,
            String quoteCoin,
            ProductCategory positionCategory,
            String symbol
    );

    default AccountAssetSnapshot tradingWalletSnapshot(
            String accountType,
            String baseCoin,
            String quoteCoin,
            ProductCategory positionCategory,
            String symbol
    ) {
        return walletSnapshot(accountType, baseCoin, quoteCoin, positionCategory, symbol);
    }
}
