/*
 * Copyright 1999-2018 Alibaba Group Holding Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.alibaba.nacos.core.distributed.distro.task.load;

import com.alibaba.nacos.core.cluster.Member;
import com.alibaba.nacos.core.cluster.ServerMemberManager;
import com.alibaba.nacos.core.distributed.distro.DistroConfig;
import com.alibaba.nacos.core.distributed.distro.component.DistroCallback;
import com.alibaba.nacos.core.distributed.distro.component.DistroComponentHolder;
import com.alibaba.nacos.core.distributed.distro.component.DistroDataProcessor;
import com.alibaba.nacos.core.distributed.distro.component.DistroTransportAgent;
import com.alibaba.nacos.core.distributed.distro.entity.DistroData;
import com.alibaba.nacos.core.utils.GlobalExecutor;
import com.alibaba.nacos.core.utils.Loggers;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Distro load data task.
 *
 * @author xiweng.yy
 */
public class DistroLoadDataTask implements Runnable {

    private final ServerMemberManager memberManager;

    private final DistroComponentHolder distroComponentHolder;

    private final DistroConfig distroConfig;

    private final DistroCallback loadCallback;

    private final Map<String, Boolean> loadCompletedMap;

    public DistroLoadDataTask(ServerMemberManager memberManager, DistroComponentHolder distroComponentHolder,
            DistroConfig distroConfig, DistroCallback loadCallback) {
        this.memberManager = memberManager;
        this.distroComponentHolder = distroComponentHolder;
        this.distroConfig = distroConfig;
        this.loadCallback = loadCallback;
        loadCompletedMap = new HashMap<>(1);
    }

    @Override
    public void run() {
        try {
            //先一次性拉取一次，注意当前线程这个不是定时执行
            load();
            //如果没有拉取到，或者拉取后与本地注册表信息还是不一致的话，就开启定时任务定时进行拉取
            if (!checkCompleted()) {
                GlobalExecutor.submitLoadDataTask(this, distroConfig.getLoadDataRetryDelayMillis());
            } else {
                loadCallback.onSuccess();
                Loggers.DISTRO.info("[DISTRO-INIT] load snapshot data success");
            }
        } catch (Exception e) {
            loadCallback.onFailed(e);
            Loggers.DISTRO.error("[DISTRO-INIT] load snapshot data failed. ", e);
        }
    }

    private void load() throws Exception {
        while (memberManager.allMembersWithoutSelf().isEmpty()) {
            Loggers.DISTRO.info("[DISTRO-INIT] waiting server list init...");
            TimeUnit.SECONDS.sleep(1);
        }
        while (distroComponentHolder.getDataStorageTypes().isEmpty()) {
            Loggers.DISTRO.info("[DISTRO-INIT] waiting distro data storage register...");
            TimeUnit.SECONDS.sleep(1);
        }
        for (String each : distroComponentHolder.getDataStorageTypes()) {
            if (!loadCompletedMap.containsKey(each) || !loadCompletedMap.get(each)) {
                loadCompletedMap.put(each, loadAllDataSnapshotFromRemote(each));
            }
        }
    }

    private boolean loadAllDataSnapshotFromRemote(String resourceType) {
        DistroTransportAgent transportAgent = distroComponentHolder.findTransportAgent(resourceType);
        DistroDataProcessor dataProcessor = distroComponentHolder.findDataProcessor(resourceType);
        if (null == transportAgent || null == dataProcessor) {
            Loggers.DISTRO.warn("[DISTRO-INIT] Can't find component for type {}, transportAgent: {}, dataProcessor: {}",
                    resourceType, transportAgent, dataProcessor);
            return false;
        }
        for (Member each : memberManager.allMembersWithoutSelf()) {
            try {
                Loggers.DISTRO.info("[DISTRO-INIT] load snapshot {} from {}", resourceType, each.getAddress());
                //从集群其它节点拉取服务实例信息，注意这里是从本地快照中（dataStore）拉取，dataStore相当于是注册表的一个缓存，在服务注册的时候生成
                DistroData distroData = transportAgent.getDatumSnapshot(each.getAddress());
                //将拉取到的服务实例信息更新到自己的注册表中
                boolean result = dataProcessor.processSnapshot(distroData);
                Loggers.DISTRO
                        .info("[DISTRO-INIT] load snapshot {} from {} result: {}", resourceType, each.getAddress(),
                                result);
                //拉取到数据之后就返回了，也就是说只会从集群中其中一个节点中拉取，没必要从其它所有节点都拉取一遍，
                // 只有当前拉取失败了，才会轮询到下个节点再拉取
                if (result) {
                    return true;
                }
            } catch (Exception e) {
                Loggers.DISTRO.error("[DISTRO-INIT] load snapshot {} from {} failed.", resourceType, each.getAddress(), e);
            }
        }
        return false;
    }

    private boolean checkCompleted() {
        if (distroComponentHolder.getDataStorageTypes().size() != loadCompletedMap.size()) {
            return false;
        }
        for (Boolean each : loadCompletedMap.values()) {
            if (!each) {
                return false;
            }
        }
        return true;
    }
}
