package com.deltaforce.agentweb.model;

public class ItemPrice {

    private String name;
    private String type;
    private long currentPrice;
    private long combatPrice;
    private long spread;
    private double spreadRate;
    private double todayChangePercent;
    private long currentFakeAccount;
    private double fakeAccountRatioPercent;
    private long price3d;
    private double change3dPercent;
    private long price7d;
    private double change7dPercent;
    private long price30d;
    private double change30dPercent;

    public ItemPrice() {
    }

    public ItemPrice(String name, String type, long currentPrice, long combatPrice) {
        this.name = name;
        this.type = type;
        this.currentPrice = currentPrice;
        this.combatPrice = combatPrice;
        this.spread = combatPrice - currentPrice;
        this.spreadRate = currentPrice == 0 ? 0 : ((double) this.spread / (double) currentPrice) * 100.0;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public long getCurrentPrice() {
        return currentPrice;
    }

    public void setCurrentPrice(long currentPrice) {
        this.currentPrice = currentPrice;
    }

    public long getCombatPrice() {
        return combatPrice;
    }

    public void setCombatPrice(long combatPrice) {
        this.combatPrice = combatPrice;
    }

    public long getSpread() {
        return spread;
    }

    public void setSpread(long spread) {
        this.spread = spread;
    }

    public double getSpreadRate() {
        return spreadRate;
    }

    public void setSpreadRate(double spreadRate) {
        this.spreadRate = spreadRate;
    }

    public double getTodayChangePercent() {
        return todayChangePercent;
    }

    public void setTodayChangePercent(double todayChangePercent) {
        this.todayChangePercent = todayChangePercent;
    }

    public long getCurrentFakeAccount() {
        return currentFakeAccount;
    }

    public void setCurrentFakeAccount(long currentFakeAccount) {
        this.currentFakeAccount = currentFakeAccount;
    }

    public double getFakeAccountRatioPercent() {
        return fakeAccountRatioPercent;
    }

    public void setFakeAccountRatioPercent(double fakeAccountRatioPercent) {
        this.fakeAccountRatioPercent = fakeAccountRatioPercent;
    }

    public long getPrice3d() {
        return price3d;
    }

    public void setPrice3d(long price3d) {
        this.price3d = price3d;
    }

    public double getChange3dPercent() {
        return change3dPercent;
    }

    public void setChange3dPercent(double change3dPercent) {
        this.change3dPercent = change3dPercent;
    }

    public long getPrice7d() {
        return price7d;
    }

    public void setPrice7d(long price7d) {
        this.price7d = price7d;
    }

    public double getChange7dPercent() {
        return change7dPercent;
    }

    public void setChange7dPercent(double change7dPercent) {
        this.change7dPercent = change7dPercent;
    }

    public long getPrice30d() {
        return price30d;
    }

    public void setPrice30d(long price30d) {
        this.price30d = price30d;
    }

    public double getChange30dPercent() {
        return change30dPercent;
    }

    public void setChange30dPercent(double change30dPercent) {
        this.change30dPercent = change30dPercent;
    }
}
