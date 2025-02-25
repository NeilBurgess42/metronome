package io.iohk.metronome.storage

import cats.free.Free
import cats.free.Free.liftF
import scodec.Codec

/** Helper methods to compose operations that strictly only do reads, no writes.
  *
  * Basically the same as `KVStore` without `put` and `delete`.
  */
object KVStoreRead {

  def unit[N]: KVStore[N, Unit] =
    pure(())

  def pure[N, A](a: A): KVStore[N, A] =
    Free.pure(a)

  def instance[N]: Ops[N] = new Ops[N] {}

  def apply[N: Ops] = implicitly[Ops[N]]

  trait Ops[N] {
    import KVStoreOp._

    type KVNamespacedOp[A] = ({ type L[A] = KVStoreReadOp[N, A] })#L[A]

    def unit: KVStore[N, Unit] = KVStore.unit[N]

    def pure[A](a: A) = KVStore.pure[N, A](a)

    def read[K: Codec, V: Codec](
        namespace: N,
        key: K
    ): KVStoreRead[N, Option[V]] =
      liftF[KVNamespacedOp, Option[V]](
        Get[N, K, V](namespace, key)
      )
  }
}
