# Model

The following types are currently supported:

## Node (node)

Associates the alarm with a node object.

There are no parameters required aside from the alarm being associated to a node.

## SNMP Interface (snmp-interface)

Requires the `ifIndex` or `ifDescr` parameters to be set.

When given a `ifIndex` it will be used as-is.

When given a `ifDescr` we will attempt to lookup the assocated `ifIndex` with the matching `ifDescr` or `ifName` on th enode.

## SNMP Interface Link (snmp-interface-link)

Requires the `aIfDescr`, `zIfDescr` and `zHostname` parameters to be set.

## BGP Peer (bgp-peer)

Requires the `bgpPeer` parameters to be set.

## VPN Tunnel (vpn-tunnel)

Requires the `peerLocalAddr`, `peerRemoteAddr` and `tunnelId` parameters to be set.

## MPLS L3 VRF (mpls-l3-vrf)

Requires the `vrfNameOid` or `vrfName` parameter to be set.

## Entity-MIB Physical Entity (ent-physical-entity)

Requires the `entPhysicalIndex` parameter to be set.

## OSPF Router (ospf-router)

Requires the `ospfRouterId` parameter to be set.

## MPLS Tunnel (mpls-tunnel)

Requires the `mpls-tunnel` parameter to be set.

## MPLS LDP Session (mpls-tunnel)

Requires the `mplsLdpEntityID` parameter to be set.