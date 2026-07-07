package com.blink.dtn.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "user_profiles")
data class UserProfile(
    @PrimaryKey val userId: String,
    val nickname: String,
    val lastSeen: Long,
    val isVip: Boolean,
    val publicKey: String = "",
    val giftRoses: Int = 0,
    val giftBears: Int = 0,
    val giftDiamonds: Int = 0,
    val giftCoffee: Int = 0,
    val giftRockets: Int = 0,
    val giftCrowns: Int = 0
)
