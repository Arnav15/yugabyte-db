// Copyright (c) YugaByte, Inc.

package com.yugabyte.yw.common;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.yugabyte.yw.forms.UniverseDefinitionTaskParams;
import com.yugabyte.yw.models.AvailabilityZone;
import com.yugabyte.yw.models.Provider;
import com.yugabyte.yw.models.Region;
import com.yugabyte.yw.models.Universe;
import com.yugabyte.yw.models.helpers.CloudSpecificInfo;
import com.yugabyte.yw.models.helpers.NodeDetails;
import com.yugabyte.yw.models.helpers.PlacementInfo;
import com.yugabyte.yw.models.helpers.PlacementInfo.PlacementAZ;
import com.yugabyte.yw.models.helpers.PlacementInfo.PlacementCloud;
import com.yugabyte.yw.models.helpers.PlacementInfo.PlacementRegion;
import com.yugabyte.yw.forms.UniverseDefinitionTaskParams.UserIntent;

public class PlacementInfoUtil {
  public static final Logger LOG = LoggerFactory.getLogger(PlacementInfoUtil.class);

  // This is the maximum number of subnets that the masters can be placed across, and need to be an
  // odd number for consensus to work.
  private static final int maxMasterSubnets = 3;

  /**
   * Helper API to set some of the non user supplied information in task params.
   *
   * @param taskParams : Universe task params.
   * @param universe : The universe details with which we are working.
   * @param customerId : Current customer's id.
   */
  public static void updateTaskParams(UniverseDefinitionTaskParams taskParams,
                                      Universe universe,
                                      Long customerId) {
    LOG.info("Setting params for universe {} : {}.", universe.universeUUID, universe.name);
    // Setup the create universe task.
    taskParams.universeUUID = universe.universeUUID;

    // Compose a unique name for the universe.
    taskParams.nodePrefix = Long.toString(customerId) + "-" + universe.name;

    // Compute and fill in the placement info.
    taskParams.placementInfo = getPlacementInfo(taskParams.userIntent);

    // Save the universe version to check for ops like edit universe as we did not lock the
    // universe and might get overwritten when this operation is finally run.
    taskParams.expectedUniverseVersion = universe.version;

    // Compute the nodes that should be configured for this operation.
    taskParams.nodeDetailsSet = configureNewNodes(taskParams.nodePrefix,
                                               getStartIndex(universe),
                                               taskParams.userIntent.numNodes,
                                               taskParams.userIntent.replicationFactor,
                                               taskParams.placementInfo);
  }

  /**
   * Configures the set of nodes to be created.
   *
   * @param nodePrefix node name prefix.
   * @param startIndex index to used for node naming.
   * @param numNodes   number of nodes desired.
   * @param numMasters number of masters among these nodes.
   * @param placementInfo desired placement info.
   *
   * @return set of node details with their placement info filled in.
   */
  private static Set<NodeDetails> configureNewNodes(String nodePrefix,
                                                    int startIndex,
                                                    int numNodes,
                                                    int numMasters,
                                                    PlacementInfo placementInfo) {
    Set<NodeDetails> newNodesSet = new HashSet<NodeDetails>();
    Map<String, NodeDetails> newNodesMap = new HashMap<String, NodeDetails>();

    // Create the names and known properties of all the cluster nodes.
    int cloudIdx = 0;
    int regionIdx = 0;
    int azIdx = 0;
    for (int nodeIdx = startIndex; nodeIdx < startIndex + numNodes; nodeIdx++) {
      NodeDetails nodeDetails = new NodeDetails();
      // Create a temporary node name. These are fixed once the operation is actually run.
      nodeDetails.nodeName = nodePrefix + "-fake-n" + nodeIdx;
      // Set the cloud.
      PlacementCloud placementCloud = placementInfo.cloudList.get(cloudIdx);
      nodeDetails.cloudInfo = new CloudSpecificInfo();
      nodeDetails.cloudInfo.cloud = placementCloud.name;
      // Set the region.
      PlacementRegion placementRegion = placementCloud.regionList.get(regionIdx);
      nodeDetails.cloudInfo.region = placementRegion.code;
      // Set the AZ and the subnet.
      PlacementAZ placementAZ = placementRegion.azList.get(azIdx);
      nodeDetails.azUuid = placementAZ.uuid;
      nodeDetails.cloudInfo.az = placementAZ.name;
      nodeDetails.cloudInfo.subnet_id = placementAZ.subnet;
      // Set the tablet server role to true.
      nodeDetails.isTserver = true;
      // Set the node id.
      nodeDetails.nodeIdx = nodeIdx;
      // We are ready to add this node.
      nodeDetails.state = NodeDetails.NodeState.ToBeAdded;
      // Add the node to the set of nodes.
      newNodesSet.add(nodeDetails);
      newNodesMap.put(nodeDetails.nodeName, nodeDetails);
      LOG.debug("Placed new node [{}] at cloud:{}, region:{}, az:{}.",
                nodeDetails.toString(), cloudIdx, regionIdx, azIdx);

      // Advance to the next az/region/cloud combo.
      azIdx = (azIdx + 1) % placementRegion.azList.size();
      regionIdx = (regionIdx + (azIdx == 0 ? 1 : 0)) % placementCloud.regionList.size();
      cloudIdx = (cloudIdx + (azIdx == 0 && regionIdx == 0 ? 1 : 0)) %
          placementInfo.cloudList.size();
    }

    // Select the masters for this cluster based on subnets.
    setMasters(newNodesMap, numMasters);

    return newNodesSet;
  }

  /**
   * Given a set of nodes and the number of masters, selects the masters and marks them as such.
   *
   * @param nodesMap   : a map of node name to NodeDetails
   * @param numMasters : the number of masters to choose
   * @return nothing
   */
  private static void setMasters(Map<String, NodeDetails> nodesMap, int numMasters) {
    // Group the cluster nodes by subnets.
    Map<String, TreeSet<String>> subnetsToNodenameMap = new HashMap<String, TreeSet<String>>();
    for (Entry<String, NodeDetails> entry : nodesMap.entrySet()) {
      String subnet = entry.getValue().cloudInfo.subnet_id;
      if (!subnetsToNodenameMap.containsKey(subnet)) {
        subnetsToNodenameMap.put(subnet, new TreeSet<String>());
      }
      TreeSet<String> nodeSet = subnetsToNodenameMap.get(subnet);
      // Add the node name into the node set.
      nodeSet.add(entry.getKey());
    }
    LOG.info("Subnet map has {}, nodesMap has {}, need {} masters.",
             subnetsToNodenameMap.size(), nodesMap.size(), numMasters);
    // Choose the masters such that we have one master per subnet if there are enough subnets.
    int numMastersChosen = 0;
    if (subnetsToNodenameMap.size() >= maxMasterSubnets) {
      for (Entry<String, TreeSet<String>> entry : subnetsToNodenameMap.entrySet()) {
        // Get one node from each subnet.
        String nodeName = entry.getValue().first();
        NodeDetails node = nodesMap.get(nodeName);
        node.isMaster = true;
        LOG.info("Chose node {} as a master from subnet {}.", nodeName, entry.getKey());
        numMastersChosen++;
        if (numMastersChosen == numMasters) {
          break;
        }
      }
    } else {
      // We do not have enough subnets. Simply pick enough masters.
      for (NodeDetails node : nodesMap.values()) {
        node.isMaster = true;
        LOG.info("Chose node {} as a master from subnet {}.",
                 node.nodeName, node.cloudInfo.subnet_id);
        numMastersChosen++;
        if (numMastersChosen == numMasters) {
          break;
        }
      }
    }
  }

  // Returns the start index for provisioning new nodes based on the current maximum node index.
  // If this is called for a new universe being created, then the start index will be 1.
  private static int getStartIndex(Universe universe) {
    Collection<NodeDetails> existingNodes = universe.getNodes();

    int maxNodeIdx = 0;
    for (NodeDetails node : existingNodes) {
      if (node.nodeIdx > maxNodeIdx) {
        maxNodeIdx = node.nodeIdx;
      }
    }

    return maxNodeIdx + 1;
  }

  private static PlacementInfo getPlacementInfo(UserIntent userIntent) {
    // We only support a replication factor of 3.
    if (userIntent.replicationFactor != 3) {
      throw new RuntimeException("Replication factor must be 3");
    }
    // Make sure the preferred region is in the list of user specified regions.
    if (userIntent.preferredRegion != null &&
        !userIntent.regionList.contains(userIntent.preferredRegion)) {
      throw new RuntimeException("Preferred region " + userIntent.preferredRegion +
              " not in user region list");
    }

    // Create the placement info object.
    PlacementInfo placementInfo = new PlacementInfo();

    // Handle the single AZ deployment case.
    if (!userIntent.isMultiAZ) {
      // Select an AZ in the required region.
      List<AvailabilityZone> azList =
              AvailabilityZone.getAZsForRegion(userIntent.regionList.get(0));
      if (azList.isEmpty()) {
        throw new RuntimeException("No AZ found for region: " + userIntent.regionList.get(0));
      }
      Collections.shuffle(azList);
      UUID azUUID = azList.get(0).uuid;
      LOG.info("Using AZ {} out of {}", azUUID, azList.size());
      // Add all replicas into the same AZ.
      for (int idx = 0; idx < userIntent.replicationFactor; idx++) {
        addPlacementZone(azUUID, placementInfo);
      }
      return placementInfo;
    }

    // If one region is specified, pick all three AZs from it. Make sure there are enough regions.
    if (userIntent.regionList.size() == 1) {
      selectAndAddPlacementZones(userIntent.regionList.get(0), placementInfo, 3);
    } else if (userIntent.regionList.size() == 2) {
      // Pick two AZs from one of the regions (preferred region if specified).
      UUID preferredRegionUUID = userIntent.preferredRegion;
      // If preferred region was not specified, then pick the region that has at least 2 zones as
      // the preferred region.
      if (preferredRegionUUID == null) {
        if (AvailabilityZone.getAZsForRegion(userIntent.regionList.get(0)).size() >= 2) {
          preferredRegionUUID = userIntent.regionList.get(0);
        } else {
          preferredRegionUUID = userIntent.regionList.get(1);
        }
      }
      selectAndAddPlacementZones(preferredRegionUUID, placementInfo, 2);

      // Pick one AZ from the other region.
      UUID otherRegionUUID = userIntent.regionList.get(0).equals(preferredRegionUUID) ?
              userIntent.regionList.get(1) :
              userIntent.regionList.get(0);
      selectAndAddPlacementZones(otherRegionUUID, placementInfo, 1);
    } else if (userIntent.regionList.size() == 3) {
      // If the user has specified three regions, pick one AZ from each region.
      for (int idx = 0; idx < 3; idx++) {
        selectAndAddPlacementZones(userIntent.regionList.get(idx), placementInfo, 1);
      }
    } else {
      throw new RuntimeException("Unsupported placement, num regions " +
          userIntent.regionList.size() + " is more than replication factor of " +
          userIntent.replicationFactor);
    }

    return placementInfo;
  }

  private static void selectAndAddPlacementZones(UUID regionUUID,
                                                 PlacementInfo placementInfo,
                                                 int numZones) {
    // Find the region object.
    Region region = Region.get(regionUUID);
    LOG.debug("Selecting and adding " + numZones + " zones in region " + region.name);
    // Find the AZs for the required region.
    List<AvailabilityZone> azList = AvailabilityZone.getAZsForRegion(regionUUID);
    if (azList.size() < numZones) {
      throw new RuntimeException("Need at least " + numZones + " zones but found only " +
              azList.size() + " for region " + region.name);
    }
    Collections.shuffle(azList);
    // Pick as many AZs as required.
    for (int idx = 0; idx < numZones; idx++) {
      addPlacementZone(azList.get(idx).uuid, placementInfo);
    }
  }

  private static void addPlacementZone(UUID zone, PlacementInfo placementInfo) {
    // Get the zone, region and cloud.
    AvailabilityZone az = AvailabilityZone.find.byId(zone);
    Region region = az.region;
    Provider cloud = region.provider;
    // Find the placement cloud if it already exists, or create a new one if one does not exist.
    PlacementCloud placementCloud = null;
    for (PlacementCloud pCloud : placementInfo.cloudList) {
      if (pCloud.uuid.equals(cloud.uuid)) {
        LOG.debug("Found cloud: " + cloud.name);
        placementCloud = pCloud;
        break;
      }
    }
    if (placementCloud == null) {
      LOG.debug("Adding cloud: " + cloud.name);
      placementCloud = new PlacementCloud();
      placementCloud.uuid = cloud.uuid;
      // TODO: fix this hardcode by creating a 'code' attribute in the cloud object.
      placementCloud.name = "aws";
      placementInfo.cloudList.add(placementCloud);
    }

    // Find the placement region if it already exists, or create a new one.
    PlacementRegion placementRegion = null;
    for (PlacementRegion pRegion : placementCloud.regionList) {
      if (pRegion.uuid.equals(region.uuid)) {
        LOG.debug("Found region: " + region.name);
        placementRegion = pRegion;
        break;
      }
    }
    if (placementRegion == null) {
      LOG.debug("Adding region: " + region.name);
      placementRegion = new PlacementRegion();
      placementRegion.uuid = region.uuid;
      placementRegion.code = region.code;
      placementRegion.name = region.name;
      placementCloud.regionList.add(placementRegion);
    }

    // Find the placement AZ in the region if it already exists, or create a new one.
    PlacementAZ placementAZ = null;
    for (PlacementAZ pAz : placementRegion.azList) {
      if (pAz.uuid.equals(az.uuid)) {
        LOG.debug("Found az: " + az.name);
        placementAZ = pAz;
        break;
      }
    }
    if (placementAZ == null) {
      LOG.debug("Adding region: " + az.name);
      placementAZ = new PlacementAZ();
      placementAZ.uuid = az.uuid;
      placementAZ.name = az.name;
      placementAZ.replicationFactor = 0;
      placementAZ.subnet = az.subnet;
      placementRegion.azList.add(placementAZ);
    }
    placementAZ.replicationFactor++;
    LOG.debug("Setting az " + az.name + " replication factor = " + placementAZ.replicationFactor);
  }
}
