package spv

import examples.spv.simulation.SimulatorFuctions
import examples.spv.{Algos, KLS16ProofSerializer, KMZProofSerializer}
import org.scalacheck.Gen
import org.scalatest.prop.{GeneratorDrivenPropertyChecks, PropertyChecks}
import org.scalatest.{Matchers, PropSpec}
import scorex.core.transaction.state.PrivateKey25519Companion
import scorex.crypto.hash.Blake2b256

import scala.util.{Failure, Try}


class ChainTests extends PropSpec
  with PropertyChecks
  with GeneratorDrivenPropertyChecks
  with Matchers
  with SPVGenerators
  with SimulatorFuctions {


  val Height = 5000
  val Difficulty = BigInt(1)
  val stateRoot = Blake2b256("")
  val minerKeys = PrivateKey25519Companion.generateKeys(stateRoot)

  val genesis = genGenesisHeader(stateRoot, minerKeys._2)
  val headerChain = genChain(Height, Difficulty, stateRoot, IndexedSeq(genesis))
  val lastBlock = headerChain.last
  val lastInnerLinks = lastBlock.interlinks

  property("SPVSimulator generate chain starting from genesis") {
    headerChain.head shouldBe genesis
  }

  property("First innerchain links is to genesis") {
    lastInnerLinks.head shouldEqual genesis.id
  }

  property("Last block interlinks are correct") {
    var currentDifficulty = Difficulty
    lastInnerLinks.length should be > 1
    lastInnerLinks.tail.foreach { id =>
      Algos.blockIdDifficulty(id) should be >= currentDifficulty
      currentDifficulty = currentDifficulty * 2
    }
  }

  property("Generated SPV proof is correct") {
    forAll(mkGen) { mk =>
      val proof = Algos.constructKLS16Proof(mk._1, mk._2, headerChain).get
      proof.valid.get
    }
  }

  property("Compare correct and incorrect SPV proofs") {
    forAll(mkGen) { mk =>
      val proof = Algos.constructKLS16Proof(mk._1, mk._2, headerChain).get
      val incompleteIntercahin = proof.innerchain.filter(e => false)
      val incorrectProof = proof.copy(innerchain = incompleteIntercahin)
      incorrectProof.valid.isSuccess shouldBe false
      (proof > incorrectProof) shouldBe true
    }
  }

  property("Compare SPV proofs with different suffix") {
    forAll(mkGen) { mk =>
      val proof = Algos.constructKLS16Proof(mk._1, mk._2, headerChain).get
      val smallerChainProof = Algos.constructKLS16Proof(mk._1, mk._2, headerChain.dropRight(1)).get
      (proof > smallerChainProof) shouldBe true
    }
  }

  property("Compare SPV proofs with fork in a suffix") {
    forAll(mkGen) { mk =>
      whenever(mk._2 > 2) {
        val commonChain = headerChain.dropRight(2)
        val block = genBlock(Difficulty, commonChain.reverse.toIndexedSeq, stateRoot, defaultId, System.currentTimeMillis())
        val smallerForkChain = commonChain :+ block
        val proof = Algos.constructKLS16Proof(mk._1, mk._2, headerChain).get
        val proof2 = Algos.constructKLS16Proof(mk._1, mk._2, smallerForkChain).get
        (proof > proof2) shouldBe true
      }
    }
  }

  property("KLS16 proof serialization") {
    forAll(mkGen) { mk =>
      val proof = Algos.constructKLS16Proof(mk._1, mk._2, headerChain).get
      val serializer = KLS16ProofSerializer
      val parsed = serializer.parseBytes(serializer.toBytes(proof)).get
      serializer.toBytes(proof) shouldEqual serializer.toBytes(parsed)
      proof.suffix.last.interlinks.flatten shouldEqual parsed.suffix.last.interlinks.flatten
      //todo more checks that suffixses are the same
    }
  }

  property("KMZ proof serialization") {
    forAll(mkGen) { mk =>
      Try {
        val proof = Algos.constructKMZProof(mk._1, mk._2, headerChain).get
        val serializer = KMZProofSerializer
        val bytes = serializer.toBytes(proof)
        val parsed = serializer.parseBytes(bytes).get
        bytes shouldEqual serializer.toBytes(parsed)
        proof.suffix.last.interlinks.flatten shouldEqual parsed.suffix.last.interlinks.flatten
      }.recoverWith {
        case e =>
          e.printStackTrace()
          System.exit(1)
          Failure(e)
      }
    }
  }


  val mkGen = for {
    m <- Gen.choose(1, 100)
    k <- Gen.choose(2, 100)
  } yield (m, k)

}