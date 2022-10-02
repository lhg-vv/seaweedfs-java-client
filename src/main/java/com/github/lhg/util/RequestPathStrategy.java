package com.github.lhg.util;

public class RequestPathStrategy {
    public static final String assignFileKey = "/dir/assign";
    public static final String checkClusterStatus = "/cluster/status";
    public static final String checkTopologyStatus = "/dir/status";
    public static final String checkVolumeStatus = "/status";
    public static final String lookupVolume = "/dir/lookup";
    public static final String forceGarbageCollection = "/vol/vacuum";
    public static final String preAllocateVolumes = "/vol/grow";
    public static final String submitFile = "/submit";
    public static final String deleteCollection = "/col/delete";
}
