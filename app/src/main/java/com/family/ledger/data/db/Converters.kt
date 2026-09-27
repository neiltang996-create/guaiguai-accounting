package com.family.ledger.data.db

import androidx.room.TypeConverter
import com.family.ledger.data.db.entity.AssetType
import com.family.ledger.data.db.entity.OwnerType
import com.family.ledger.data.db.entity.TxnSource
import com.family.ledger.data.db.entity.TxnType

class Converters {
    @TypeConverter fun ownerToString(v: OwnerType?): String? = v?.name
    @TypeConverter fun stringToOwner(v: String?): OwnerType? =
        v?.let { runCatching { OwnerType.valueOf(it) }.getOrNull() }

    @TypeConverter fun assetTypeToString(v: AssetType?): String? = v?.name
    @TypeConverter fun stringToAssetType(v: String?): AssetType? =
        v?.let { runCatching { AssetType.valueOf(it) }.getOrNull() }

    @TypeConverter fun txnTypeToString(v: TxnType?): String? = v?.name
    @TypeConverter fun stringToTxnType(v: String?): TxnType? =
        v?.let { runCatching { TxnType.valueOf(it) }.getOrNull() }

    @TypeConverter fun txnSourceToString(v: TxnSource?): String? = v?.name
    @TypeConverter fun stringToTxnSource(v: String?): TxnSource? =
        v?.let { runCatching { TxnSource.valueOf(it) }.getOrNull() }
}
