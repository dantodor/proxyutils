package com.karasiq.proxychain

import java.net.{InetAddress, InetSocketAddress}
import java.nio.channels.SocketChannel

import com.karasiq.networkutils.proxy.Proxy
import com.karasiq.proxy.{ProxyConnector, ProxyException}
import com.typesafe.config.Config
import org.apache.commons.io.IOUtils

import scala.annotation.tailrec
import scala.language.implicitConversions
import scala.util.Random
import scala.util.control.Exception

object ProxyChain {
  private[proxychain] def resolved(p: Proxy): InetSocketAddress = p.toInetSocketAddress

  private[proxychain] def unresolved(p: Proxy): InetSocketAddress = InetSocketAddress.createUnresolved(p.host, p.port)

  private def proxyFromString(s: String): Proxy = {
    Proxy(if (s.contains("://")) s else s"http://$s")
  }

  def apply(proxies: Proxy*): ProxyChain = {
    if (proxies.isEmpty) new EmptyProxyChain
    else new ProxyChainImpl(proxies)
  }

  def chainFrom(proxies: Seq[Proxy], randomize: Boolean = false, hops: Int = 0): Seq[Proxy] = {
    val ordered = if (randomize) Random.shuffle(proxies) else proxies
    if (hops == 0) ordered else ordered.take(hops)
  }

  private def selectProxies(config: Config): Seq[Proxy] = {
    import scala.collection.JavaConversions._
    val proxies: IndexedSeq[String] = config.getStringList("proxies").toIndexedSeq
    chainFrom(proxies.map(proxyFromString), config.getBoolean("randomize"), config.getInt("hops"))
  }

  @throws[IllegalArgumentException]("if invalid config provided")
  def config(config: Config): ProxyChain = {
    val chain: Seq[Proxy] = Seq(config.getConfig("entry"), config.getConfig("middle"), config.getConfig("exit")).flatMap(selectProxies)
    apply(chain: _*)
  }
}

abstract class ProxyChain {
  /**
   * Creates connection through proxy chain
   * @param address Destination address
   * @return Opened connection
   */
  @throws[ProxyException]("if connection failed")
  def connection(address: InetSocketAddress): SocketChannel

  def proxies: Seq[Proxy]

  override def equals(obj: scala.Any): Boolean = obj match {
    case pc: ProxyChain ⇒ pc.proxies == this.proxies
    case _ ⇒ false
  }

  override def hashCode(): Int = {
    proxies.hashCode()
  }

  override def toString: String = {
    s"ProxyChain(${proxies.mkString("[", " -> ", "]")})"
  }
}

sealed private class EmptyProxyChain extends ProxyChain {
  /**
   * Creates direct connection
   * @param address Destination address
   * @return Opened connection
   */
  override def connection(address: InetSocketAddress): SocketChannel = {
    SocketChannel.open(new InetSocketAddress(InetAddress.getByName(address.getHostString), address.getPort))
  }

  override def proxies: Seq[Proxy] = Nil
}

@throws[IllegalArgumentException]("if proxy chain is empty")
sealed private class ProxyChainImpl(val proxies: Seq[Proxy]) extends ProxyChain {

  import ProxyChain._

  if (proxies.isEmpty) throw new IllegalArgumentException("Proxy chain shouldn't be empty")

  @inline
  private def connect(socket: SocketChannel, proxy: Proxy, destination: InetSocketAddress): Unit = {
    ProxyConnector(proxy).connect(socket, destination)
  }

  private def tryConnect(socket: SocketChannel, proxy: Proxy, address: InetSocketAddress): SocketChannel = {
    val catcher = Exception.nonFatalCatch.withApply { exc ⇒
      IOUtils.closeQuietly(socket)
      throw new ProxyException(s"Connect through $proxy to $address failed", exc)
    }

    catcher {
      connect(socket, proxy, address)
      socket
    }
  }

  /**
   * Creates connection through proxy chain
   * @param address Destination address
   * @return Opened connection
   */
  @throws[ProxyException]("if connection failed")
  def connection(address: InetSocketAddress): SocketChannel = {
    @tailrec
    def proxyConnect(socket: SocketChannel, proxies: List[Proxy], address: InetSocketAddress): SocketChannel = {
      proxies match {
        case proxy :: Nil ⇒
          // Last proxy reached, connect to destination address
          tryConnect(socket, proxy, address)

        case proxy :: (tail @ (connectTo :: _)) ⇒
          val sc = tryConnect(socket, proxy, unresolved(connectTo))
          proxyConnect(sc, tail, address)
      }
    }

    val socketChannel: SocketChannel = {
      val sc = SocketChannel.open(resolved(proxies.head))
      val socket = sc.socket()
      socket.setKeepAlive(true)
      socket.setSoTimeout(60000)
      sc
    }

    proxyConnect(socketChannel, proxies.toList, address)
  }
}