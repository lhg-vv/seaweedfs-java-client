package com.github.lhg.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.lhg.core.content.*;
import com.github.lhg.core.http.JsonResponse;
import com.github.lhg.core.topology.DataCenter;
import com.github.lhg.core.topology.GarbageResult;
import com.github.lhg.core.topology.SystemTopologyStatus;
import com.github.lhg.exception.SeaweedfsException;
import com.github.lhg.util.RequestPathStrategy;
import com.github.lhg.util.Utils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.http.HttpEntity;
import org.apache.http.HttpStatus;
import org.apache.http.client.methods.HttpDelete;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.mime.HttpMultipartMode;
import org.apache.http.entity.mime.MultipartEntityBuilder;
import org.apache.http.message.BasicHeader;
import org.apache.http.util.CharsetUtils;
import org.ehcache.Cache;
import org.ehcache.CacheManager;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/**
 * Master接口包装类，本类中包括了所有master API
 */
public class MasterWrapper {
    private static final Log log = LogFactory.getLog(MasterWrapper.class);
    private Connection connection;
    private Cache<Long, LookupVolumeResult> lookupVolumeCache;
    private ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 构造器.
     *
     * @param connection SeaweedFS的HTTP连接
     */
    public MasterWrapper(Connection connection) {
        this.connection = connection;
        final CacheManager cacheManager = connection.getCacheManager();
        if (cacheManager != null) {
            this.lookupVolumeCache =
                    cacheManager.getCache(Connection.LOOKUP_VOLUME_CACHE_ALIAS, Long.class, LookupVolumeResult.class);
        }
    }


    /**
     * 调度master的分配fid接口
     *
     * @param params 请求参数.
     * @return SeaweedFS申请fid接口返回对象.
     * @throws IOException Http connection is fail or server response within some error message.
     */
    public AssignFileKeyResult assignFileKey(AssignFileKeyParams params) throws IOException {
        checkConnection();
        //增加可用volume的判断
        SystemTopologyStatus systemTopologyStatus = connection.getSystemTopologyStatus();
        List<DataCenter> dataCenterList = systemTopologyStatus.getDataCenters();
        params.setDataCenter(getOneAvailableDataCenter(dataCenterList).getId());
        log.info(" datacenter " + getOneAvailableDataCenter(dataCenterList) + " url " + params.toUrlParams());
        final String url = connection.getLeaderUrl() + RequestPathStrategy.assignFileKey + params.toUrlParams();
        HttpGet request = new HttpGet(url);
        JsonResponse jsonResponse = connection.fetchJsonResultByRequest(request);
        return objectMapper.readValue(jsonResponse.json, AssignFileKeyResult.class);
    }


    /***
     * 得到可用的datacenter
     * @param  dataCenterList 当前的数据volume集合
     * @return 得到一个可用的集合
     * **/
    private DataCenter getOneAvailableDataCenter(List<DataCenter> dataCenterList) {
        for (DataCenter dataCenter : dataCenterList) {
            if (dataCenter.getFree() != 0) {
                return dataCenter;
            }
        }
        //如果走到这里，说明也没有可用的空间，直接返回第一个存储volume
        return dataCenterList.get(0);
    }

    /**
     * 强制进行垃圾回收，GC接口。
     * 当你的系统进行了大量删除操作的时候，已经分配的空间并不会立刻回收，回收工作会有一个线程在后台慢慢进行。
     * 如果空闲空间小于30%，回收进程会把volume置为只读状态，并新建一个volume，并切换到新的volume上。
     * 本接口将会强制回收这些空间
     *
     * @param params GC接口必要参数.
     * @throws IOException Http connection is fail or server response within some error message.
     */
    public GarbageResult forceGarbageCollection(ForceGarbageCollectionParams params) throws IOException {
        checkConnection();
        final String url = connection.getLeaderUrl() + RequestPathStrategy.forceGarbageCollection + params.toUrlParams();
        HttpGet request = new HttpGet(url);
        JsonResponse jsonResponse = connection.fetchJsonResultByRequest(request);
        String json = jsonResponse.json;
        return Utils.convertJsonToEntity(json, GarbageResult.class);
    }

    /**
     * 预分配Volume接口。
     * 一个Volume一次只能处理一个写请求。如果想要提升并发量，使用这个接口预先分配一些volume
     *
     * @param params 必须的参数.
     * @return 分配接口返回值对象
     * @throws IOException Http connection is fail or server response within some error message.
     */
    public PreAllocateVolumesResult preAllocateVolumes(PreAllocateVolumesParams params) throws IOException {
        checkConnection();
        final String url = connection.getLeaderUrl() + RequestPathStrategy.preAllocateVolumes + params.toUrlParams();
        HttpGet request = new HttpGet(url);
        JsonResponse jsonResponse = connection.fetchJsonResultByRequest(request);
        return objectMapper.readValue(jsonResponse.json, PreAllocateVolumesResult.class);
    }


    /**
     * 检查volume状态的接口.
     *
     * @param params 请求参数.
     * @return 接口返回对象.
     * @throws IOException Http connection is fail or server response within some error message.
     */
    public LookupVolumeResult lookupVolume(LookupVolumeParams params) throws IOException {
        checkConnection();
        LookupVolumeResult result;
        if (this.lookupVolumeCache != null) {
            result = this.lookupVolumeCache.get(params.getVolumeId());
            if (result == null) {
                result = fetchLookupVolumeResult(params);
                this.lookupVolumeCache.put(params.getVolumeId(), result);
            }
            return result;
        } else {
            return fetchLookupVolumeResult(params);
        }
    }

    /**
     * 不用请求fid，直接上传文件接口
     * @param url submit接口地址
     * @param fileName 文件名称
     * @param stream 文件流
     * @return 返回一个SubmitFileResult对象
     * @throws IOException HTTP请求可能出现的IOException
     */
    public SubmitFileResult uploadFileDirectly(String url, String fileName, InputStream stream) throws IOException {
        HttpPost request = new HttpPost(url + RequestPathStrategy.submitFile);
        MultipartEntityBuilder builder = MultipartEntityBuilder.create();

        request.setHeader(new BasicHeader("Accept-Language", "zh-cn"));

        builder.setMode(HttpMultipartMode.BROWSER_COMPATIBLE);
        builder.setCharset(CharsetUtils.get("UTF-8"));
        builder.addBinaryBody("upload", stream, ContentType.DEFAULT_BINARY, fileName);
        HttpEntity entity = builder.build();
        request.setEntity(entity);
        JsonResponse jsonResponse = connection.fetchJsonResultByRequest(request);
        if (jsonResponse == null) {
            jsonResponse = new JsonResponse("{\"name\":\"" + fileName + "\",\"size\":0}", HttpStatus.SC_OK);
        }
        Utils.convertResponseStatusToException(jsonResponse.statusCode, url, false, false, false, false);
        String response = jsonResponse.json;
        return Utils.convertJsonToEntity(response, SubmitFileResult.class);
    }

    public int deleteCollection(String url, String collection) throws IOException {
        String deleteUrl = url + RequestPathStrategy.deleteCollection + "?collection=" + collection;
        HttpDelete request = new HttpDelete(deleteUrl);
        JsonResponse jsonResponse = connection.fetchJsonResultByRequest(request);
        return jsonResponse.statusCode;

    }

    /**
     * Fetch lookup volume result.
     *
     * @param params fetch lookup volume params.
     * @return fetch lookup volume result.
     * @throws IOException Http connection is fail or server response within some error message.
     */
    private LookupVolumeResult fetchLookupVolumeResult(LookupVolumeParams params) throws IOException {
        checkConnection();
        final String url = connection.getLeaderUrl() + RequestPathStrategy.lookupVolume + params.toUrlParams();
        HttpGet request = new HttpGet(url);
        JsonResponse jsonResponse = connection.fetchJsonResultByRequest(request);
        return objectMapper.readValue(jsonResponse.json, LookupVolumeResult.class);
    }

    /**
     * Check connection is alive.
     *
     * @throws SeaweedfsException Http connection is fail.
     */
    private void checkConnection() throws SeaweedfsException {
        if (this.connection.isConnectionClose()) {
            throw new SeaweedfsException("connection is closed");
        }
    }


}
