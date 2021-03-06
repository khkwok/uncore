// See LICENSE for license details.

package uncore
import Chisel._

// Classes to represent coherence information in clients and managers
abstract class CoherenceMetadata extends Bundle {
  val co = params(TLCoherencePolicy)
  val id = params(TLId)
}

/*  The ClientMetadata stores the client-side coherence information,
    such as permissions on the data and whether the data is dirty.
    Its API can be used to make TileLink messages in response to
    memory operations or TileLink Probes.
*/
class ClientMetadata extends CoherenceMetadata {
  val state = UInt(width = co.clientStateWidth)

  def ===(rhs: ClientMetadata): Bool = this.state === rhs.state

  def isValid(dummy: Int = 0): Bool = co.isValid(this)
  def isHit(cmd: UInt): Bool = co.isHit(cmd, this)
  def isMiss(cmd: UInt): Bool = !co.isHit(cmd, this)
  def requiresAcquireOnSecondaryMiss(first_cmd: UInt, second_cmd: UInt): Bool =
    co.requiresAcquireOnSecondaryMiss(first_cmd, second_cmd, this)
  def requiresReleaseOnCacheControl(cmd: UInt): Bool =
    co.requiresReleaseOnCacheControl(cmd: UInt, this)
  def requiresVoluntaryWriteback(dummy: Int = 0): Bool =
    co.requiresReleaseOnCacheControl(M_FLUSH, this)

  def makeAcquire(
      client_xact_id: UInt,
      addr_block: UInt,
      op_code: UInt): Acquire = {
    Bundle(Acquire(
        is_builtin_type = Bool(false),
        a_type = co.getAcquireType(op_code, this),
        client_xact_id = client_xact_id,
        addr_block = addr_block,
        union = Cat(op_code, Bool(true))),
      { case TLId => id })
  }

  def makeVoluntaryWriteback(
      client_xact_id: UInt,
      addr_block: UInt,
      addr_beat: UInt = UInt(0),
      data: UInt = UInt(0)): Release = {
    Bundle(Release(
      voluntary = Bool(true),
      r_type = co.getReleaseType(M_FLUSH, this),
      client_xact_id = client_xact_id,
      addr_block = addr_block,
      addr_beat = addr_beat,
      data = data), { case TLId => id })
  }

  def makeRelease(
      prb: Probe,
      client_xact_id: UInt,
      addr_beat: UInt = UInt(0),
      data: UInt = UInt(0)): Release = {
    Bundle(Release(
      voluntary = Bool(false),
      r_type = co.getReleaseType(prb, this),
      client_xact_id = client_xact_id,
      addr_block = prb.addr_block,
      addr_beat = addr_beat,
      data = data), { case TLId => id })
  }

  def onGrant(incoming: Grant, pending: UInt): ClientMetadata =
    Bundle(co.clientMetadataOnGrant(incoming, pending, this), { case TLId => id })
  def onProbe(incoming: Probe): ClientMetadata =
    Bundle(co.clientMetadataOnProbe(incoming, this), { case TLId => id })
  def onHit(cmd: UInt): ClientMetadata =
    Bundle(co.clientMetadataOnHit(cmd, this), { case TLId => id })
  def onCacheControl(cmd: UInt): ClientMetadata =
    Bundle(co.clientMetadataOnCacheControl(cmd, this), { case TLId => id })
}

object ClientMetadata {
  def apply(state: UInt) = {
    val meta = new ClientMetadata
    meta.state := state
    meta
  }
  def onReset = new ClientMetadata().co.clientMetadataOnReset
}

/* The ManagerMetadata stores manager-side information about the status 
    of a cache block, including whether it has any known sharers. Its
    API can be used to create Probe and Grant TileLink messages.
*/
class ManagerMetadata extends CoherenceMetadata {
  // val state = UInt(width = co.masterStateWidth) TODO: Fix 0-width wires in Chisel
  val sharers = UInt(width = co.dir.width)

  def ===(rhs: ManagerMetadata): Bool = //this.state === rhs.state && TODO: Fix 0-width wires in Chisel
                                         this.sharers === rhs.sharers
  def full(dummy: Int = 0) = co.dir.full(this.sharers)

  def requiresProbes(acq: Acquire): Bool = co.requiresProbes(acq, this)
  def requiresProbes(cmd: UInt): Bool = co.requiresProbes(cmd, this)
  def requiresProbesOnVoluntaryWriteback(dummy: Int = 0): Bool =
    co.requiresProbes(M_FLUSH, this)

  def makeProbe(cmd: UInt, addr_block: UInt): Probe = 
    Bundle(Probe(co.getProbeType(cmd, this), addr_block), { case TLId => id })

  def makeProbe(acq: Acquire): Probe = 
    Bundle(Probe(co.getProbeType(acq, this), acq.addr_block), { case TLId => id })

  def makeProbeForVoluntaryWriteback(addr_block: UInt): Probe =
    makeProbe(M_FLUSH, addr_block)

  def makeGrant(rel: Release, manager_xact_id: UInt): Grant = {
    Bundle(Grant(
      is_builtin_type = Bool(true),
      g_type = Grant.voluntaryAckType,
      client_xact_id = rel.client_xact_id,
      manager_xact_id = manager_xact_id), { case TLId => id })
  }

  def makeGrant(
      acq: Acquire,
      manager_xact_id: UInt, 
      addr_beat: UInt = UInt(0),
      data: UInt = UInt(0)): Grant = {
    Bundle(Grant(
      is_builtin_type = acq.isBuiltInType(),
      g_type = Mux(acq.isBuiltInType(), 
                     acq.getBuiltInGrantType(),
                     co.getGrantType(acq, this)),
      client_xact_id = acq.client_xact_id,
      manager_xact_id = manager_xact_id,
      addr_beat = addr_beat,
      data = data), { case TLId => id })
  }

  def onRelease(incoming: Release, src: UInt): ManagerMetadata =
    Bundle(co.managerMetadataOnRelease(incoming, src, this), { case TLId => id })

  def onGrant(outgoing: Grant, dst: UInt): ManagerMetadata =
    Bundle(co.managerMetadataOnGrant(outgoing, dst, this), { case TLId => id })
}

object ManagerMetadata {
  def apply(sharers: UInt, state: UInt = UInt(width = 0)) = {
    val meta = new ManagerMetadata
    //meta.state := state TODO: Fix 0-width wires in Chisel 
    meta.sharers := sharers
    meta
  }
  def apply() = {
    val meta = new ManagerMetadata
    //meta.state := UInt(width = 0) TODO: Fix 0-width wires in Chisel 
    meta.sharers := meta.co.dir.flush
    meta
  }
  def onReset = new ManagerMetadata().co.managerMetadataOnReset
}

/* HierarchicalMetadata is used in a cache in a multi-level memory hierarchy
    that is a Manager with respect to some inner caches and a Client with
    respect to some outer cache.
*/ 
class HierarchicalMetadata extends CoherenceMetadata {
  val inner: ManagerMetadata = Bundle(new ManagerMetadata, {case TLId => params(InnerTLId)})
  val outer: ClientMetadata = Bundle(new ClientMetadata, {case TLId => params(OuterTLId)})
  def ===(rhs: HierarchicalMetadata): Bool = 
    this.inner === rhs.inner && this.outer === rhs.outer
  def !=(rhs: HierarchicalMetadata): Bool = !(this === rhs)
}

object HierarchicalMetadata {
  def apply(inner: ManagerMetadata, outer: ClientMetadata): HierarchicalMetadata = {
    val m = new HierarchicalMetadata
    m.inner := inner
    m.outer := outer
    m
  }
  def onReset: HierarchicalMetadata = apply(ManagerMetadata.onReset, ClientMetadata.onReset)
}

case object InnerTLId extends Field[String]
case object OuterTLId extends Field[String]
