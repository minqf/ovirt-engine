package org.ovirt.engine.core.bll.scheduling;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import javax.annotation.PostConstruct;
import javax.enterprise.concurrent.ManagedScheduledExecutorService;
import javax.inject.Inject;
import javax.inject.Singleton;

import org.apache.commons.lang.exception.ExceptionUtils;
import org.apache.commons.lang.math.NumberUtils;
import org.ovirt.engine.core.bll.HostLocking;
import org.ovirt.engine.core.bll.VmHandler;
import org.ovirt.engine.core.bll.network.host.NetworkDeviceHelper;
import org.ovirt.engine.core.bll.network.host.VfScheduler;
import org.ovirt.engine.core.bll.scheduling.external.BalanceResult;
import org.ovirt.engine.core.bll.scheduling.external.ExternalSchedulerBroker;
import org.ovirt.engine.core.bll.scheduling.external.ExternalSchedulerDiscovery;
import org.ovirt.engine.core.bll.scheduling.external.WeightResultEntry;
import org.ovirt.engine.core.bll.scheduling.pending.PendingCpuCores;
import org.ovirt.engine.core.bll.scheduling.pending.PendingHugePages;
import org.ovirt.engine.core.bll.scheduling.pending.PendingMemory;
import org.ovirt.engine.core.bll.scheduling.pending.PendingNumaMemory;
import org.ovirt.engine.core.bll.scheduling.pending.PendingOvercommitMemory;
import org.ovirt.engine.core.bll.scheduling.pending.PendingResourceManager;
import org.ovirt.engine.core.bll.scheduling.pending.PendingVM;
import org.ovirt.engine.core.bll.scheduling.policyunits.RankSelectorPolicyUnit;
import org.ovirt.engine.core.bll.scheduling.selector.SelectorInstance;
import org.ovirt.engine.core.bll.scheduling.utils.CpuPinningHelper;
import org.ovirt.engine.core.bll.scheduling.utils.NumaPinningHelper;
import org.ovirt.engine.core.common.AuditLogType;
import org.ovirt.engine.core.common.BackendService;
import org.ovirt.engine.core.common.businessentities.Cluster;
import org.ovirt.engine.core.common.businessentities.NumaTuneMode;
import org.ovirt.engine.core.common.businessentities.VDS;
import org.ovirt.engine.core.common.businessentities.VDSStatus;
import org.ovirt.engine.core.common.businessentities.VM;
import org.ovirt.engine.core.common.businessentities.VdsNumaNode;
import org.ovirt.engine.core.common.businessentities.VmNumaNode;
import org.ovirt.engine.core.common.businessentities.VmStatic;
import org.ovirt.engine.core.common.config.Config;
import org.ovirt.engine.core.common.config.ConfigValues;
import org.ovirt.engine.core.common.errors.EngineMessage;
import org.ovirt.engine.core.common.scheduling.ClusterPolicy;
import org.ovirt.engine.core.common.scheduling.OptimizationType;
import org.ovirt.engine.core.common.scheduling.PerHostMessages;
import org.ovirt.engine.core.common.scheduling.PolicyUnit;
import org.ovirt.engine.core.common.scheduling.PolicyUnitType;
import org.ovirt.engine.core.common.scheduling.VmOverheadCalculator;
import org.ovirt.engine.core.common.utils.HugePageUtils;
import org.ovirt.engine.core.common.utils.Pair;
import org.ovirt.engine.core.compat.Guid;
import org.ovirt.engine.core.dal.dbbroker.auditloghandling.AuditLogDirector;
import org.ovirt.engine.core.dal.dbbroker.auditloghandling.AuditLogable;
import org.ovirt.engine.core.dal.dbbroker.auditloghandling.AuditLogableImpl;
import org.ovirt.engine.core.dal.dbbroker.auditloghandling.MessageBundler;
import org.ovirt.engine.core.dao.ClusterDao;
import org.ovirt.engine.core.dao.VdsDao;
import org.ovirt.engine.core.dao.VdsNumaNodeDao;
import org.ovirt.engine.core.dao.VmNumaNodeDao;
import org.ovirt.engine.core.dao.scheduling.ClusterPolicyDao;
import org.ovirt.engine.core.dao.scheduling.PolicyUnitDao;
import org.ovirt.engine.core.di.Injector;
import org.ovirt.engine.core.utils.threadpool.ThreadPoolUtil;
import org.ovirt.engine.core.utils.threadpool.ThreadPools;
import org.ovirt.engine.core.vdsbroker.ResourceManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Singleton
public class SchedulingManager implements BackendService {

    private static final Logger log = LoggerFactory.getLogger(SchedulingManager.class);
    private static final String HIGH_UTILIZATION = "HighUtilization";
    private static final String LOW_UTILIZATION = "LowUtilization";

    @Inject
    private AuditLogDirector auditLogDirector;
    @Inject
    private ResourceManager resourceManager;
    @Inject
    private MigrationHandler migrationHandler;
    @Inject
    private ExternalSchedulerDiscovery exSchedulerDiscovery;
    @Inject
    private VdsDao vdsDao;
    @Inject
    private VmHandler vmHandler;
    @Inject
    private ClusterDao clusterDao;
    @Inject
    private PolicyUnitDao policyUnitDao;
    @Inject
    private ClusterPolicyDao clusterPolicyDao;
    @Inject
    private NetworkDeviceHelper networkDeviceHelper;
    @Inject
    private HostLocking hostLocking;
    @Inject
    private VmOverheadCalculator vmOverheadCalculator;
    @Inject
    private ExternalSchedulerBroker externalBroker;
    @Inject
    private VfScheduler vfScheduler;
    @Inject
    private VmNumaNodeDao vmNumaNodeDao;
    @Inject
    private VdsNumaNodeDao vdsNumaNodeDao;
    @Inject
    @ThreadPools(ThreadPools.ThreadPoolType.EngineScheduledThreadPool)
    private ManagedScheduledExecutorService executor;

    private PendingResourceManager pendingResourceManager;

    /**
     * [policy id, policy] map
     */
    private final ConcurrentHashMap<Guid, ClusterPolicy> policyMap;
    /**
     * [policy unit id, policy unit] map
     */
    private volatile ConcurrentHashMap<Guid, PolicyUnitImpl> policyUnits;

    private final Object policyUnitsLock = new Object();

    private final ConcurrentHashMap<Guid, Semaphore> clusterLockMap = new ConcurrentHashMap<>();

    private final RunVmDelayer noWaitingVmDelayer = new NonWaitingDelayer();

    private final Map<Guid, Boolean> clusterId2isHaReservationSafe = new HashMap<>();

    private final Guid defaultSelectorGuid = InternalPolicyUnits.getGuid(RankSelectorPolicyUnit.class);

    private PendingResourceManager getPendingResourceManager() {
        return pendingResourceManager;
    }

    @Inject
    protected SchedulingManager() {
        policyMap = new ConcurrentHashMap<>();
        policyUnits = new ConcurrentHashMap<>();
    }

    @PostConstruct
    public void init() {
        log.info("Initializing Scheduling manager");
        initializePendingResourceManager();
        loadPolicyUnits();
        loadClusterPolicies();
        loadExternalScheduler();
        enableLoadBalancer();
        enableHaReservationCheck();
        log.info("Initialized Scheduling manager");
    }

    private void initializePendingResourceManager() {
        pendingResourceManager = new PendingResourceManager(resourceManager);
    }

    private void loadExternalScheduler() {
        if (Config.<Boolean>getValue(ConfigValues.ExternalSchedulerEnabled)) {
            log.info("Starting external scheduler discovery thread");

            /* Disable all external units, this is needed in case an external scheduler broker
               implementation is missing, because nobody would then disable units that
               were registered by the missing broker */
            exSchedulerDiscovery.markAllExternalPoliciesAsDisabled();

            ThreadPoolUtil.execute(() -> {
                if (exSchedulerDiscovery.discover()) {
                    reloadPolicyUnits();
                }
            });
        } else {
            exSchedulerDiscovery.markAllExternalPoliciesAsDisabled();
            log.info("External scheduler disabled, discovery skipped");
        }
    }

    private void reloadPolicyUnits() {
        synchronized (policyUnitsLock) {
            policyUnits = new ConcurrentHashMap<>();
            loadPolicyUnits();
        }
    }

    public List<ClusterPolicy> getClusterPolicies() {
        return new ArrayList<>(policyMap.values());
    }

    public ClusterPolicy getClusterPolicy(Guid clusterPolicyId) {
        return policyMap.get(clusterPolicyId);
    }

    public Optional<ClusterPolicy> getClusterPolicy(String name) {
        if (name == null || name.isEmpty()) {
            return Optional.empty();
        }
        for (ClusterPolicy clusterPolicy : policyMap.values()) {
            if (clusterPolicy.getName().toLowerCase().equals(name.toLowerCase())) {
                return Optional.of(clusterPolicy);
            }
        }
        return Optional.empty();
    }

    public ClusterPolicy getDefaultClusterPolicy() {
        for (ClusterPolicy clusterPolicy : policyMap.values()) {
            if (clusterPolicy.isDefaultPolicy()) {
                return clusterPolicy;
            }
        }

        // This should never happen, there must be at least one InternalClusterPolicy
        // that is marked as default. InternalClusterPoliciesTest.testDefaultPolicy()
        // makes sure exactly one is defined
        throw new RuntimeException("There is no system default cluster policy!");
    }

    public Map<Guid, PolicyUnitImpl> getPolicyUnitsMap() {
        synchronized (policyUnitsLock) {
            return policyUnits;
        }
    }

    private void loadClusterPolicies() {
        // Load internal cluster policies
        policyMap.putAll(InternalClusterPolicies.getClusterPolicies());

        Map<Guid, PolicyUnitType> internalTypes = new HashMap<>();
        for (PolicyUnitImpl unit: policyUnits.values()) {
            internalTypes.put(unit.getGuid(), unit.getType());
        }

        // Get all user provided cluster policies
        List<ClusterPolicy> allClusterPolicies = clusterPolicyDao.getAll(
                Collections.unmodifiableMap(internalTypes));

        for (ClusterPolicy clusterPolicy : allClusterPolicies) {
            policyMap.put(clusterPolicy.getId(), clusterPolicy);
        }
    }

    private void loadPolicyUnits() {
        // Load internal policy units
        for (Class<? extends PolicyUnitImpl> unitType: InternalPolicyUnits.getList()) {
            try {
                PolicyUnitImpl unit = InternalPolicyUnits.instantiate(unitType, getPendingResourceManager());
                policyUnits.put(unit.getGuid(), Injector.injectMembers(unit));
            } catch (Exception e){
                log.error("Could not instantiate a policy unit {}.", unitType.getName(), e);
            }
        }

        // Load all external policy units
        List<PolicyUnit> allPolicyUnits = policyUnitDao.getAll();
        for (PolicyUnit policyUnit : allPolicyUnits) {
            policyUnits.put(policyUnit.getId(), new ExternalPolicyUnit(policyUnit, getPendingResourceManager()));
        }
    }

    private static class SchedulingResult {
        Map<Guid, Pair<EngineMessage, String>> filteredOutReasons;
        Map<Guid, String> hostNames;
        PerHostMessages details;

        public SchedulingResult() {
            filteredOutReasons = new HashMap<>();
            hostNames = new HashMap<>();
            details = new PerHostMessages();
        }

        public void addReason(Guid id, String hostName, EngineMessage filterType, String filterName) {
            filteredOutReasons.put(id, new Pair<>(filterType, filterName));
            hostNames.put(id, hostName);
        }

        public Collection<String> getReasonMessages() {
            List<String> lines = new ArrayList<>();

            for (Entry<Guid, Pair<EngineMessage, String>> line: filteredOutReasons.entrySet()) {
                lines.add(line.getValue().getFirst().name());
                lines.add(String.format("$%1$s %2$s", "hostName", hostNames.get(line.getKey())));
                lines.add(String.format("$%1$s %2$s", "filterName", line.getValue().getSecond()));

                final List<String> detailMessages = details.getMessages(line.getKey());
                if (detailMessages.isEmpty()) {
                    lines.add(EngineMessage.SCHEDULING_HOST_FILTERED_REASON.name());
                } else {
                    lines.addAll(detailMessages);
                    lines.add(EngineMessage.SCHEDULING_HOST_FILTERED_REASON_WITH_DETAIL.name());
                }
            }

            return lines;
        }

        private PerHostMessages getDetails() {
            return details;
        }

    }

    public Optional<Guid> schedule(Cluster cluster,
            VM vm,
            List<Guid> hostBlackList,
            List<Guid> hostWhiteList,
            List<Guid> destHostIdList,
            List<String> messages,
            RunVmDelayer runVmDelayer,
            String correlationId) {
        prepareClusterLock(cluster.getId());
        try {
            log.debug("Scheduling started, correlation Id: {}", correlationId);
            checkAllowOverbooking(cluster);
            lockCluster(cluster.getId());
            List<VDS> vdsList = vdsDao
                    .getAllForClusterWithStatus(cluster.getId(), VDSStatus.Up);
            vdsList = removeBlacklistedHosts(vdsList, hostBlackList);
            vdsList = keepOnlyWhitelistedHosts(vdsList, hostWhiteList);
            refreshCachedPendingValues(vdsList);
            subtractRunningVmResources(cluster, vm, vdsList);
            ClusterPolicy policy = policyMap.get(cluster.getClusterPolicyId());
            Map<String, String> parameters = createClusterPolicyParameters(cluster);

            vdsList =
                    runFilters(policy.getFilters(),
                            cluster,
                            vdsList,
                            vm,
                            parameters,
                            policy.getFilterPositionMap(),
                            messages,
                            runVmDelayer,
                            true,
                            correlationId);

            if (vdsList.isEmpty()) {
                return Optional.empty();
            }

            Optional<Guid> bestHost = selectBestHost(cluster, vm, destHostIdList, vdsList, policy, parameters);
            if (bestHost.isPresent() && !bestHost.get().equals(vm.getRunOnVds())) {
                Guid bestHostId = bestHost.get();
                addPendingResources(vm, bestHostId);
                markVfsAsUsedByVm(vm, bestHostId);
            }

            return bestHost;
        } catch (InterruptedException e) {
            log.error("scheduling interrupted, correlation Id: {}: {}", correlationId, e.getMessage());
            log.debug("Exception: ", e);
            return Optional.empty();
        } finally {
            releaseCluster(cluster.getId());

            log.debug("Scheduling ended, correlation Id: {}", correlationId);
        }
    }

    private void addPendingResources(VM vm, Guid hostId) {
        getPendingResourceManager().addPending(new PendingCpuCores(hostId, vm, vm.getNumOfCpus()));
        getPendingResourceManager().addPending(new PendingMemory(hostId, vm, vmOverheadCalculator.getStaticOverheadInMb(vm)));
        getPendingResourceManager().addPending(new PendingOvercommitMemory(hostId, vm, vmOverheadCalculator.getTotalRequiredMemoryInMb(vm)));
        getPendingResourceManager().addPending(new PendingVM(hostId, vm));

        addPendingNumaMemory(vm, hostId);

        // Add pending records for all specified hugepage sizes
        for (Map.Entry<Integer, Integer> hugepage: HugePageUtils.getHugePages(vm.getStaticData()).entrySet()) {
            getPendingResourceManager().addPending(new PendingHugePages(hostId, vm,
                    hugepage.getKey(), hugepage.getValue()));
        }

        getPendingResourceManager().notifyHostManagers(hostId);
    }

    /**
     * Adds NUMA node assignment to pending resources.
     *
     * The assignment is only one of the possible assignments.
     * The real one used by libvirt can be different, but the engine does not know it.
     *
     * When starting many VMs with NUMA pinning, it may happen that some of them will
     * not pass scheduling, even if they could fit on the host.
     */
    private void addPendingNumaMemory(VM vm, Guid hostId) {
        if (vm.getNumaTuneMode() == NumaTuneMode.PREFERRED) {
            return;
        }

        List<VmNumaNode> vmNodes = vmNumaNodeDao.getAllVmNumaNodeByVmId(vm.getId());
        List<VdsNumaNode> hostNodes = vdsNumaNodeDao.getAllVdsNumaNodeByVdsId(hostId);

        Map<Integer, Collection<Integer>> cpuPinning = CpuPinningHelper.parseCpuPinning(vm.getCpuPinning()).stream()
                .collect(Collectors.toMap(p -> p.getvCpu(), p -> p.getpCpus()));

        Optional<Map<Integer, Integer>> nodeAssignment = Optional.empty();
        if (!cpuPinning.isEmpty()) {
            nodeAssignment = NumaPinningHelper.findAssignment(vmNodes, hostNodes, cpuPinning);
        }

        if (!nodeAssignment.isPresent()) {
            nodeAssignment = NumaPinningHelper.findAssignment(vmNodes, hostNodes);
        }

        if (!nodeAssignment.isPresent()) {
            return;
        }

        Map<Integer, Long> hostNodePending = new HashMap<>(hostNodes.size());
        for (VmNumaNode vmNode: vmNodes) {
            // Ignore unpinned numa nodes
            if (vmNode.getVdsNumaNodeList().isEmpty()) {
                continue;
            }

            int hostNodeIndex = nodeAssignment.get().get(vmNode.getIndex());
            long vmNodeMem = vmNode.getMemTotal();

            hostNodePending.merge(hostNodeIndex, vmNodeMem, Long::sum);
        }

        for (Map.Entry<Integer, Long> entry: hostNodePending.entrySet()) {
            int hostNodeIndex = entry.getKey();
            long pendingMemory = entry.getValue();
            getPendingResourceManager().addPending(new PendingNumaMemory(hostId, vm, hostNodeIndex, pendingMemory));
        }
    }

    private void releaseCluster(Guid cluster) {
        // ensuring setting the semaphore permits to 1
        synchronized (clusterLockMap.get(cluster)) {
            clusterLockMap.get(cluster).drainPermits();
            clusterLockMap.get(cluster).release();
        }
    }

    private void lockCluster(Guid cluster) throws InterruptedException {
        clusterLockMap.get(cluster).acquire();
    }

    private void prepareClusterLock(Guid cluster) {
        clusterLockMap.putIfAbsent(cluster, new Semaphore(1));
    }

    private void markVfsAsUsedByVm(VM vm, Guid bestHostId) {
        Map<Guid, String> passthroughVnicToVfMap = vfScheduler.getVnicToVfMap(vm.getId(), bestHostId);
        if (passthroughVnicToVfMap == null || passthroughVnicToVfMap.isEmpty()) {
            return;
        }

        try {
            hostLocking.acquireHostDevicesLock(bestHostId);
            Collection<String> virtualFunctions = passthroughVnicToVfMap.values();

            log.debug("Marking following VF as used by VM({}) on selected host({}): {}",
                    vm.getId(),
                    bestHostId,
                    virtualFunctions);

            networkDeviceHelper.setVmIdOnVfs(bestHostId, vm.getId(), new HashSet<>(virtualFunctions));
        } finally {
            hostLocking.releaseHostDevicesLock(bestHostId);
        }
    }

    /**
     * Refresh cached VDS pending fields with the current pending
     * values from PendingResourceManager.
     * @param vdsList - list of candidate hosts
     */
    private void refreshCachedPendingValues(List<VDS> vdsList) {
        for (VDS vds: vdsList) {
            int pendingMemory = PendingOvercommitMemory.collectForHost(getPendingResourceManager(), vds.getId());
            int pendingCpuCount = PendingCpuCores.collectForHost(getPendingResourceManager(), vds.getId());

            vds.setPendingVcpusCount(pendingCpuCount);
            vds.setPendingVmemSize(pendingMemory);
        }
    }

    /**
     * @param destHostIdList - used for RunAt preselection, overrides the ordering in vdsList
     * @param availableVdsList - presorted list of hosts (better hosts first) that are available
     */
    private Optional<Guid> selectBestHost(Cluster cluster,
            VM vm,
            List<Guid> destHostIdList,
            List<VDS> availableVdsList,
            ClusterPolicy policy,
            Map<String, String> parameters) {
        // in case a default destination host was specified and
        // it passed filters, return the first found
        List<VDS> runnableHosts = new LinkedList<>();
        if (destHostIdList.size() > 0) {
            // there are dedicated hosts
            // intersect dedicated hosts list with available list
            for (VDS vds : availableVdsList) {
                for (Guid destHostId : destHostIdList) {
                    if (destHostId.equals(vds.getId())) {
                        runnableHosts.add(vds);
                    }
                }
            }
        }
        if (runnableHosts.isEmpty()) { // no dedicated hosts found
            runnableHosts = availableVdsList;
        }

        switch (runnableHosts.size()){
        case 0:
            // no runnable hosts found, nothing found
            return Optional.empty();
        case 1:
            // found single available host, in available list return it
            return Optional.of(runnableHosts.get(0).getId());
        default:
            // select best runnable host with scoring functions (from policy)
            List<Pair<Guid, Integer>> functions = policy.getFunctions();
            Guid selector = Optional.of(policy).map(ClusterPolicy::getSelector).orElse(defaultSelectorGuid);
            PolicyUnitImpl selectorUnit = policyUnits.get(selector);
            SelectorInstance selectorInstance = selectorUnit.selector(parameters);

            List<Guid> runnableGuids = runnableHosts.stream().map(VDS::getId).collect(Collectors.toList());
            selectorInstance.init(functions, runnableGuids);

            if (!functions.isEmpty()
                    && shouldWeighClusterHosts(cluster, runnableHosts)) {
                Optional<Guid> bestHostByFunctions = runFunctions(selectorInstance, functions, cluster,
                        runnableHosts, vm, parameters);
                if (bestHostByFunctions.isPresent()) {
                    return bestHostByFunctions;
                }
            }
        }
        // failed select best runnable host using scoring functions, return the first
        return Optional.of(runnableHosts.get(0).getId());
    }

    /**
     * Checks whether scheduler should schedule several requests in parallel:
     * Conditions:
     * * config option SchedulerAllowOverBooking should be enabled.
     * * cluster optimization type flag should allow over-booking.
     * * more than than X (config.SchedulerOverBookingThreshold) pending for scheduling.
     * In case all of the above conditions are met, we release all the pending scheduling
     * requests.
     */
    private void checkAllowOverbooking(Cluster cluster) {
        if (OptimizationType.ALLOW_OVERBOOKING == cluster.getOptimizationType()
                && Config.<Boolean>getValue(ConfigValues.SchedulerAllowOverBooking)
                && clusterLockMap.get(cluster.getId()).getQueueLength() >=
                Config.<Integer>getValue(ConfigValues.SchedulerOverBookingThreshold)) {
            log.info("Scheduler: cluster '{}' lock is skipped (cluster is allowed to overbook)",
                    cluster.getName());
            // release pending threads (requests) and current one (+1)
            clusterLockMap.get(cluster.getId())
                    .release(Config.<Integer>getValue(ConfigValues.SchedulerOverBookingThreshold) + 1);
        }
    }

    /**
     * Checks whether scheduler should weigh hosts/or skip weighing:
     * * More than one host (it's trivial to weigh a single host).
     * * optimize for speed is enabled for the cluster, and there are less than configurable requests pending (skip
     * weighing in a loaded setup).
     */
    private boolean shouldWeighClusterHosts(Cluster cluster, List<VDS> vdsList) {
        Integer threshold = Config.<Integer>getValue(ConfigValues.SpeedOptimizationSchedulingThreshold);
        // threshold is crossed only when cluster is configured for optimized for speed
        boolean crossedThreshold =
                OptimizationType.OPTIMIZE_FOR_SPEED == cluster.getOptimizationType()
                        && clusterLockMap.get(cluster.getId()).getQueueLength() >
                        threshold;
        if (crossedThreshold) {
            log.info(
                    "Scheduler: skipping whinging hosts in cluster '{}', since there are more than '{}' parallel requests",
                    cluster.getName(),
                    threshold);
        }
        return vdsList.size() > 1
                && !crossedThreshold;
    }

    public List<VDS> canSchedule(Cluster cluster,
            VM vm,
            List<Guid> vdsBlackList,
            List<Guid> vdsWhiteList,
            List<String> messages) {
        List<VDS> vdsList = vdsDao
                .getAllForClusterWithStatus(cluster.getId(), VDSStatus.Up);
        vdsList = removeBlacklistedHosts(vdsList, vdsBlackList);
        vdsList = keepOnlyWhitelistedHosts(vdsList, vdsWhiteList);
        refreshCachedPendingValues(vdsList);
        subtractRunningVmResources(cluster, vm, vdsList);
        ClusterPolicy policy = policyMap.get(cluster.getClusterPolicyId());
        Map<String, String> parameters = createClusterPolicyParameters(cluster);

        vdsList =
                runFilters(policy.getFilters(),
                        cluster,
                        vdsList,
                        vm,
                        parameters,
                        policy.getFilterPositionMap(),
                        messages,
                        noWaitingVmDelayer,
                        false,
                        null);

        return vdsList;
    }

    private Map<String, String> createClusterPolicyParameters(Cluster cluster) {
        Map<String, String> parameters = new HashMap<>();
        if (cluster.getClusterPolicyProperties() != null) {
            parameters.putAll(cluster.getClusterPolicyProperties());
        }
        return parameters;
    }

    /**
     * Remove hosts from vdsList that are not present on the whitelist
     *
     * Empty white list signalizes that nothing is to be done.
     *
     * @param vdsList List of hosts to filter
     * @param list Whitelist
     */
    private List<VDS> keepOnlyWhitelistedHosts(List<VDS> vdsList, List<Guid> list) {
        if (!list.isEmpty()) {
            Set<Guid> listSet = new HashSet<>(list);

            return vdsList.stream()
                    .filter(host -> listSet.contains(host.getId()))
                    .collect(Collectors.toList());
        } else {
            return vdsList;
        }
    }

    /**
     * Remove hosts from vdsList that are present on the blacklist
     *
     * Empty black list signalizes that nothing is to be done.
     *
     * @param vdsList List of hosts to filter
     * @param list Blacklist
     */
    private List<VDS> removeBlacklistedHosts(List<VDS> vdsList, List<Guid> list) {
        if (!list.isEmpty()) {
            Set<Guid> listSet = new HashSet<>(list);

            return vdsList.stream()
                    .filter(host -> !listSet.contains(host.getId()))
                    .collect(Collectors.toList());
        } else {
            return vdsList;
        }
    }

    private List<VDS> runFilters(List<Guid> filters,
            Cluster cluster,
            List<VDS> hostList,
            VM vm,
            Map<String, String> parameters,
            Map<Guid, Integer> filterPositionMap,
            List<String> messages,
            RunVmDelayer runVmDelayer,
            boolean shouldRunExternalFilters,
            String correlationId) {
        SchedulingResult result = new SchedulingResult();
        List<PolicyUnitImpl> internalFilters = new ArrayList<>();
        List<PolicyUnitImpl> externalFilters = new ArrayList<>();

        // Create a local copy so we can manipulate it
        filters = new ArrayList<>(filters);

        sortFilters(filters, filterPositionMap);
        for (Guid filter : filters) {
            PolicyUnitImpl filterPolicyUnit = policyUnits.get(filter);
            if (filterPolicyUnit.getPolicyUnit().isInternal()) {
                internalFilters.add(filterPolicyUnit);
            } else {
                if (filterPolicyUnit.getPolicyUnit().isEnabled()) {
                    externalFilters.add(filterPolicyUnit);
                }
            }
        }

        /* Short circuit filters if there are no hosts at all */
        if (hostList.isEmpty()) {
            messages.add(EngineMessage.SCHEDULING_NO_HOSTS.name());
            messages.addAll(result.getReasonMessages());
            return hostList;
        }

        hostList =
                runInternalFilters(internalFilters, cluster, hostList, vm, parameters, runVmDelayer, correlationId, result);

        if (shouldRunExternalFilters
                && Config.<Boolean>getValue(ConfigValues.ExternalSchedulerEnabled)
                && !externalFilters.isEmpty()
                && !hostList.isEmpty()) {
            hostList = runExternalFilters(externalFilters, hostList, vm, parameters, correlationId, result);
        }

        if (hostList.isEmpty()) {
            messages.add(EngineMessage.SCHEDULING_ALL_HOSTS_FILTERED_OUT.name());
            messages.addAll(result.getReasonMessages());
        }
        return hostList;
    }

    private List<VDS> runInternalFilters(List<PolicyUnitImpl> filters,
            Cluster cluster,
            List<VDS> hostList,
            VM vm,
            Map<String, String> parameters,
            RunVmDelayer runVmDelayer,
            String correlationId,
            SchedulingResult result) {
        for (PolicyUnitImpl filterPolicyUnit : filters) {
            if (hostList.isEmpty()) {
                break;
            }
            filterPolicyUnit.setRunVmDelayer(runVmDelayer);
            List<VDS> currentHostList = new ArrayList<>(hostList);
            hostList = filterPolicyUnit.filter(cluster, hostList, vm, parameters, result.getDetails());
            logFilterActions(currentHostList,
                    toIdSet(hostList),
                    EngineMessage.VAR__FILTERTYPE__INTERNAL,
                    filterPolicyUnit.getPolicyUnit().getName(),
                    result,
                    correlationId);
        }
        return hostList;
    }

    private Set<Guid> toIdSet(List<VDS> hostList) {
        return hostList.stream().map(VDS::getId).collect(Collectors.toSet());
    }

    private void logFilterActions(List<VDS> oldList,
                                  Set<Guid> newSet,
                                  EngineMessage actionName,
                                  String filterName,
                                  SchedulingResult result,
                                  String correlationId) {
        for (VDS host: oldList) {
            if (!newSet.contains(host.getId())) {
                result.addReason(host.getId(), host.getName(), actionName, filterName);
                log.info("Candidate host '{}' ('{}') was filtered out by '{}' filter '{}' (correlation id: {})",
                        host.getName(),
                        host.getId(),
                        actionName.name(),
                        filterName,
                        correlationId);
            }
        }
    }

    private List<VDS> runExternalFilters(List<PolicyUnitImpl> filters,
            List<VDS> hostList,
            VM vm,
            Map<String, String> parameters,
            String correlationId,
            SchedulingResult result) {

        List<Guid> hostIDs = hostList.stream().map(VDS::getId).collect(Collectors.toList());

        List<String> filterNames = filters.stream()
                .filter(f -> !f.getPolicyUnit().isInternal())
                .map(f -> f.getPolicyUnit().getName())
                .collect(Collectors.toList());

        List<Guid> filteredIDs =
                externalBroker.runFilters(filterNames, hostIDs, vm.getId(), parameters);
        logFilterActions(hostList,
                new HashSet<>(filteredIDs),
                EngineMessage.VAR__FILTERTYPE__EXTERNAL,
                Arrays.toString(filterNames.toArray()),
                result,
                correlationId);
        hostList = intersectHosts(hostList, filteredIDs);

        return hostList;
    }

    private List<VDS> intersectHosts(List<VDS> hosts, List<Guid> IDs) {
        Set<Guid> idSet = new HashSet<>(IDs);
        return hosts.stream().filter(host -> idSet.contains(host.getId())).collect(Collectors.toList());
    }

    private void sortFilters(List<Guid> filters, final Map<Guid, Integer> filterPositionMap) {
        filters.sort(Comparator.comparingInt(f -> filterPositionMap.getOrDefault(f, 0)));
    }

    private Optional<Guid> runFunctions(SelectorInstance selector,
            List<Pair<Guid, Integer>> functions,
            Cluster cluster,
            List<VDS> hostList,
            VM vm,
            Map<String, String> parameters) {
        List<Pair<PolicyUnitImpl, Integer>> internalScoreFunctions = new ArrayList<>();
        List<Pair<PolicyUnitImpl, Integer>> externalScoreFunctions = new ArrayList<>();

        for (Pair<Guid, Integer> pair : functions) {
            PolicyUnitImpl currentPolicy = policyUnits.get(pair.getFirst());
            if (currentPolicy.getPolicyUnit().isInternal()) {
                internalScoreFunctions.add(new Pair<>(currentPolicy, pair.getSecond()));
            } else {
                if (currentPolicy.getPolicyUnit().isEnabled()) {
                    externalScoreFunctions.add(new Pair<>(currentPolicy, pair.getSecond()));
                }
            }
        }

        runInternalFunctions(selector, internalScoreFunctions, cluster, hostList,
                vm, parameters);

        if (Config.<Boolean>getValue(ConfigValues.ExternalSchedulerEnabled) && !externalScoreFunctions.isEmpty()) {
            runExternalFunctions(selector, externalScoreFunctions, hostList, vm, parameters);
        }

        return selector.best();
    }

    private void runInternalFunctions(SelectorInstance selector,
            List<Pair<PolicyUnitImpl, Integer>> functions,
            Cluster cluster,
            List<VDS> hostList,
            VM vm,
            Map<String, String> parameters) {

        for (Pair<PolicyUnitImpl, Integer> pair : functions) {
            List<Pair<Guid, Integer>> scoreResult = pair.getFirst().score(cluster, hostList, vm, parameters);
            for (Pair<Guid, Integer> result : scoreResult) {
                selector.record(pair.getFirst().getGuid(), result.getFirst(), result.getSecond());
            }
        }
    }

    private void runExternalFunctions(SelectorInstance selector,
            List<Pair<PolicyUnitImpl, Integer>> functions,
            List<VDS> hostList,
            VM vm,
            Map<String, String> parameters) {
        List<Guid> hostIDs = hostList.stream().map(VDS::getId).collect(Collectors.toList());

        List<Pair<String, Integer>> scoreNameAndWeight = functions.stream()
                .filter(pair -> !pair.getFirst().getPolicyUnit().isInternal())
                .map(pair -> new Pair<>(pair.getFirst().getName(), pair.getSecond()))
                .collect(Collectors.toList());

        Map<String, Guid> nameToGuidMap = functions.stream()
                .filter(pair -> !pair.getFirst().getPolicyUnit().isInternal())
                .collect(Collectors.toMap(pair -> pair.getFirst().getPolicyUnit().getName(),
                        pair -> pair.getFirst().getPolicyUnit().getId()));

        List<WeightResultEntry> externalScores =
                externalBroker.runScores(scoreNameAndWeight,
                        hostIDs,
                        vm.getId(),
                        parameters);

        sumScoreResults(selector, nameToGuidMap, externalScores);
    }

    private void sumScoreResults(SelectorInstance selector,
            Map<String, Guid> nametoGuidMap,
            List<WeightResultEntry> externalScores) {
        for (WeightResultEntry resultEntry : externalScores) {
            // The old external scheduler returns summed up data without policy unit identification, treat
            // it as a single policy unit with id null
            selector.record(nametoGuidMap.getOrDefault(resultEntry.getWeightUnit(), null),
                    resultEntry.getHost(), resultEntry.getWeight());
        }
    }

    private void subtractRunningVmResources(Cluster cluster, VM vm, List<VDS> hosts) {
        // Check if VM is running
        if (Guid.isNullOrEmpty(vm.getRunOnVds())) {
            return;
        }

        // Find host where it runs
        VDS host = hosts.stream()
                 .filter(h -> h.getId().equals(vm.getRunOnVds()))
                 .findAny().orElse(null);

        if (host == null) {
            return;
        }

        vmHandler.updateVmStatistics(vm);
        // There may be no statistics for the VM, if it was started recently
        if (vm.getStatisticsData() == null) {
            return;
        }

        // Using the count of threads instead of count of CPUs,
        // because the CPU percentage from the host uses threads
        int hostThreads = host.getCpuThreads();
        int hostLoad = host.getUsageCpuPercent() * hostThreads;
        int vmLoad = vm.getUsageCpuPercent() * vm.getNumOfCpus(true);

        host.setUsageCpuPercent(Math.max(
                Math.round((float) (hostLoad - vmLoad) / (float) hostThreads),
                0));

        // Subtract memory
        host.setMemCommited(host.getMemCommited() - vmOverheadCalculator.getTotalRequiredMemoryInMb(vm));
    }

    public Map<String, String> getCustomPropertiesRegexMap(ClusterPolicy clusterPolicy) {
        Set<Guid> usedPolicyUnits = new HashSet<>();
        if (clusterPolicy.getFilters() != null) {
            usedPolicyUnits.addAll(clusterPolicy.getFilters());
        }
        if (clusterPolicy.getFunctions() != null) {
            for (Pair<Guid, Integer> pair : clusterPolicy.getFunctions()) {
                usedPolicyUnits.add(pair.getFirst());
            }
        }
        if (clusterPolicy.getBalance() != null) {
            usedPolicyUnits.add(clusterPolicy.getBalance());
        }
        Map<String, String> map = new LinkedHashMap<>();
        for (Guid policyUnitId : usedPolicyUnits) {
            map.putAll(policyUnits.get(policyUnitId).getPolicyUnit().getParameterRegExMap());
        }
        return map;
    }

    public void addClusterPolicy(ClusterPolicy clusterPolicy) {
        clusterPolicyDao.save(clusterPolicy);
        policyMap.put(clusterPolicy.getId(), clusterPolicy);
    }

    public void editClusterPolicy(ClusterPolicy clusterPolicy) {
        clusterPolicyDao.update(clusterPolicy);
        policyMap.put(clusterPolicy.getId(), clusterPolicy);
    }

    public void removeClusterPolicy(Guid clusterPolicyId) {
        clusterPolicyDao.remove(clusterPolicyId);
        policyMap.remove(clusterPolicyId);
    }

    private void enableLoadBalancer() {
        if (Config.<Boolean>getValue(ConfigValues.EnableVdsLoadBalancing)) {
            log.info("Start scheduling to enable vds load balancer");
            executor.scheduleWithFixedDelay(this::performLoadBalancing,
                    Config.<Long>getValue(ConfigValues.VdsLoadBalancingIntervalInMinutes),
                    Config.<Long>getValue(ConfigValues.VdsLoadBalancingIntervalInMinutes),
                    TimeUnit.MINUTES);
            log.info("Finished scheduling to enable vds load balancer");
        }
    }

    private void enableHaReservationCheck() {

        if (Config.<Boolean>getValue(ConfigValues.EnableVdsLoadBalancing)) {
            log.info("Start HA Reservation check");
            long interval = Config.<Long> getValue(ConfigValues.VdsHaReservationIntervalInMinutes);
            executor.scheduleWithFixedDelay(this::performHaResevationCheck,
                    interval,
                    interval,
                    TimeUnit.MINUTES);
            log.info("Finished HA Reservation check");
        }

    }

    private void performHaResevationCheck() {
        try {
            performHaResevationCheckImpl();
        } catch (Throwable t) {
            log.error("Exception in performing HA Reservation check: {}", ExceptionUtils.getRootCauseMessage(t));
            log.debug("Exception", t);
        }
    }

    public void performHaResevationCheckImpl() {

        log.debug("HA Reservation check timer entered.");
        List<Cluster> clusters = clusterDao.getAll();
        if (clusters != null) {
            HaReservationHandling haReservationHandling = new HaReservationHandling(getPendingResourceManager());
            for (Cluster cluster : clusters) {
                if (cluster.supportsHaReservation()) {
                    List<VDS> returnedFailedHosts = new ArrayList<>();
                    boolean clusterHaStatus =
                            haReservationHandling.checkHaReservationStatusForCluster(cluster, returnedFailedHosts);
                    if (!clusterHaStatus) {
                        // create Alert using returnedFailedHosts
                        AuditLogable logable = createEventForCluster(cluster);
                        String failedHostsStr =
                                returnedFailedHosts.stream().map(VDS::getName).collect(Collectors.joining(", "));

                        logable.addCustomValue("Hosts", failedHostsStr);
                        auditLogDirector.log(logable, AuditLogType.CLUSTER_ALERT_HA_RESERVATION);
                        log.info("Cluster '{}' fail to pass HA reservation check.", cluster.getName());
                    }

                    boolean clusterHaStatusFromPreviousCycle =
                            clusterId2isHaReservationSafe.getOrDefault(cluster.getId(), true);

                    // Update the status map with the new status
                    clusterId2isHaReservationSafe.put(cluster.getId(), clusterHaStatus);

                    // Create Alert if the status was changed from false to true
                    if (!clusterHaStatusFromPreviousCycle && clusterHaStatus) {
                        AuditLogable logable = createEventForCluster(cluster);
                        auditLogDirector.log(logable, AuditLogType.CLUSTER_ALERT_HA_RESERVATION_DOWN);
                    }
                }
            }
        }
        log.debug("HA Reservation check timer finished.");
    }

    private AuditLogable createEventForCluster(Cluster cluster) {
        AuditLogable logable = new AuditLogableImpl();
        logable.setClusterName(cluster.getName());
        logable.setClusterId(cluster.getId());
        return logable;
    }

    private void performLoadBalancing() {
        try {
            performLoadBalancingImpl();
        } catch (Throwable t) {
            log.error("Exception in performing load balancing: {}", ExceptionUtils.getRootCauseMessage(t));
            log.debug("Exception", t);
        }
    }

    private void performLoadBalancingImpl() {
        log.debug("Load Balancer timer entered.");
        List<Cluster> clusters = clusterDao.getAll();
        for (Cluster cluster : clusters) {
            ClusterPolicy policy = policyMap.get(cluster.getClusterPolicyId());
            PolicyUnitImpl policyUnit = policyUnits.get(policy.getBalance());
            List<BalanceResult> balanceResults = Collections.emptyList();
            if (policyUnit.getPolicyUnit().isEnabled()) {
                List<VDS> hosts = vdsDao.getAllForClusterWithoutMigrating(cluster.getId());
                if (policyUnit.getPolicyUnit().isInternal()) {
                    balanceResults = internalRunBalance(policyUnit, cluster, hosts);
                } else if (Config.<Boolean> getValue(ConfigValues.ExternalSchedulerEnabled)) {
                    balanceResults = externalRunBalance(policyUnit, cluster, hosts);
                }
            }

            for (BalanceResult balanceResult: balanceResults) {
                if (!balanceResult.isValid()) {
                    continue;
                }

                boolean migrated = migrationHandler.migrateVM(balanceResult.getCandidateHosts(),
                        balanceResult.getVmToMigrate(),
                        MessageBundler.getMessage(AuditLogType.MIGRATION_REASON_LOAD_BALANCING));

                if (migrated) {
                    break;
                }
            }
        }
    }

    private List<BalanceResult> internalRunBalance(PolicyUnitImpl policyUnit,
            Cluster cluster,
            List<VDS> hosts) {
        return policyUnit.balance(cluster,
                hosts,
                cluster.getClusterPolicyProperties());
    }

    private List<BalanceResult> externalRunBalance(PolicyUnitImpl policyUnit,
            Cluster cluster,
            List<VDS> hosts) {
        List<Guid> hostIDs = new ArrayList<>();
        for (VDS vds : hosts) {
            hostIDs.add(vds.getId());
        }

        Optional<BalanceResult> balanceResult = externalBroker.runBalance(policyUnit.getPolicyUnit().getName(),
                hostIDs, cluster.getClusterPolicyProperties());

        if (balanceResult.isPresent()) {
            return Collections.singletonList(balanceResult.get());
        }

        log.warn("All external schedulers returned empty balancing result.");
        return Collections.emptyList();
    }

    /**
     * returns all cluster policies names containing the specific policy unit.
     * @return List of cluster policy names that use the referenced policyUnitId
     *         or null if the policy unit is not available.
     */
    public List<String> getClusterPoliciesNamesByPolicyUnitId(Guid policyUnitId) {
        List<String> list = new ArrayList<>();
        final PolicyUnitImpl policyUnitImpl = policyUnits.get(policyUnitId);
        if (policyUnitImpl == null) {
            log.warn("Trying to find usages of non-existing policy unit '{}'", policyUnitId);
            return null;
        }

        PolicyUnit policyUnit = policyUnitImpl.getPolicyUnit();
        if (policyUnit != null) {
            for (ClusterPolicy clusterPolicy : policyMap.values()) {
                switch (policyUnit.getPolicyUnitType()) {
                case FILTER:
                    Collection<Guid> filters = clusterPolicy.getFilters();
                    if (filters != null && filters.contains(policyUnitId)) {
                        list.add(clusterPolicy.getName());
                    }
                    break;
                case WEIGHT:
                    Collection<Pair<Guid, Integer>> functions = clusterPolicy.getFunctions();
                    if (functions == null) {
                        break;
                    }
                    for (Pair<Guid, Integer> pair : functions) {
                        if (pair.getFirst().equals(policyUnitId)) {
                            list.add(clusterPolicy.getName());
                            break;
                        }
                    }
                    break;
                case LOAD_BALANCING:
                    if (policyUnitId.equals(clusterPolicy.getBalance())) {
                        list.add(clusterPolicy.getName());
                    }
                    break;
                default:
                    break;
                }
            }
        }
        return list;
    }

    public void removeExternalPolicyUnit(Guid policyUnitId) {
        policyUnitDao.remove(policyUnitId);
        policyUnits.remove(policyUnitId);
    }

    /**
     * update host scheduling statistics:
     * * CPU load duration interval over/under policy threshold
     */
    public void updateHostSchedulingStats(VDS vds) {
        if (vds.getUsageCpuPercent() != null) {
            Cluster cluster = clusterDao.get(vds.getClusterId());
            if (vds.getUsageCpuPercent() >= NumberUtils.toInt(cluster.getClusterPolicyProperties()
                    .get(HIGH_UTILIZATION),
                    Config.<Integer> getValue(ConfigValues.HighUtilizationForEvenlyDistribute))
                    || vds.getUsageCpuPercent() <= NumberUtils.toInt(cluster.getClusterPolicyProperties()
                            .get(LOW_UTILIZATION),
                            Config.<Integer> getValue(ConfigValues.LowUtilizationForEvenlyDistribute))) {
                if (vds.getCpuOverCommitTimestamp() == null) {
                    vds.setCpuOverCommitTimestamp(new Date());
                }
            } else {
                vds.setCpuOverCommitTimestamp(null);
            }
        }
    }

    /**
     * Clear pending records for a VM.
     *
     * While scheduling a VM, this function may be called by a different thread
     * when another VM successfully starts. As an effect, policy units can see
     * different states of pending resources.
     * This is OK, because clearing pending resources should only increase the
     * number of possible hosts that can run the VM.
     */
    public void clearPendingVm(VmStatic vm) {
        getPendingResourceManager().clearVm(vm);
    }
}
