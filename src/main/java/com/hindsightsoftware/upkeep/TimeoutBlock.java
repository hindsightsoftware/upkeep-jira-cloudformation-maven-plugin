package com.hindsightsoftware.upkeep;

public abstract class TimeoutBlock {
    private final long maxWaitTime;
    private final long tryDelay;

    public TimeoutBlock(long maxWaitTime, long tryDelay) {
        this.maxWaitTime = maxWaitTime;
        this.tryDelay = tryDelay;
    }

    public boolean run(){
        long startTime = System.currentTimeMillis();
        while(System.currentTimeMillis() - startTime < maxWaitTime * 1000){
            try {
                if (!block()) {
                    try {
                        Thread.sleep(tryDelay);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                        return false;
                    }
                } else {
                    return true;
                }
            } catch (Exception e){
                e.printStackTrace();
            }
        }

        System.err.println("Maximum wait time of " + maxWaitTime + " reached!");
        return false;
    }

    public abstract boolean block() throws Exception;
}
