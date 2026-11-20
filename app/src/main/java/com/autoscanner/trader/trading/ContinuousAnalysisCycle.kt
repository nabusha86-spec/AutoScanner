package com.autoscanner.trader.trading

internal class ContinuousAnalysisCycle {
    private var running = false
    private var cycleActive = false

    fun start(): Boolean {
        if (running) return false
        running = true
        return true
    }

    fun beginCycle(): Boolean {
        if (!running || cycleActive) return false
        cycleActive = true
        return true
    }

    fun finishCycle(): Boolean {
        if (!cycleActive) return false
        cycleActive = false
        return running
    }

    fun stop() {
        running = false
        cycleActive = false
    }
}
