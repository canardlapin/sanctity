package sanctity.policy

import java.net.InetSocketAddress
import java.nio.file.{Files, Path}
import java.security.KeyStore
import java.util.concurrent.CountDownLatch
import scala.jdk.CollectionConverters.*

/** Separate protected witness JVM; only this test entry point can pause durable writes. */
object PolicyProcessWitness:
  import PolicyWire.*
  def main(args: Array[String]): Unit =
    val config = parse(Files.readAllBytes(Path.of("/trust/witness.json")))
    val root = Path.of(text(config,"store"))
    val key = privateKey(Files.readAllBytes(Path.of(text(config,"signingPrivate"))))
    val public = publicKey(Files.readAllBytes(Path.of(text(config,"signingPublic"))))
    val installation = text(config,"installation")
    def records: Vector[Path] =
      val entries = Files.list(root)
      try entries.iterator().asScala.filter(_.getFileName.toString.startsWith("record-")).toVector.sorted
      finally entries.close()
    def signedWrite(path: Path, body: io.circe.Json): Unit = Files.write(path,canonical(sign(body,key,public))): Unit
    if args.head == "init" then PolicyWitnessStore.initialize(root,installation,key,public)
    else if args.head == "alter" then
      val paths = records
      args(1) match
        case "gap" => Files.delete(paths(1))
        case "reorder" =>
          val a = Files.readAllBytes(paths(1)); val b = Files.readAllBytes(paths(2))
          Files.write(paths(1),b); Files.write(paths(2),a)
        case "payload" =>
          val value = parse(Files.readAllBytes(paths.last))
          val changed = value.mapObject(o => o.add("signature",str(base64(Array.fill[Byte](64)(0)))))
          Files.write(paths.last,canonical(changed))
        case "capability" =>
          val value = verify(parse(Files.readAllBytes(paths.head)),public)
          signedWrite(paths.head,value.mapObject(o => o.add("command",field(value,"command").mapObject(_.add("capabilities",str(zero))))))
        case "foreign" =>
          val value = verify(parse(Files.readAllBytes(root.resolve("identity.json"))),public)
          signedWrite(root.resolve("identity.json"),value.mapObject(_.add("installation",str(java.util.UUID.randomUUID().toString))))
        case _ => throw new IllegalArgumentException("alteration")
    else
      val store = PolicyWitnessStore.openObserved(root,installation,key,public,stage =>
        val control = Path.of("/work/control.json")
        if Files.exists(control) then
          val instruction = parse(Files.readAllBytes(control))
          val record = verify(parse(Files.readAllBytes(records.last)),public)
          if text(instruction,"kind") == text(record,"kind") && text(instruction,"stage") == stage then
            Files.writeString(Path.of("/work/boundary.json"),obj("stage" -> str(stage),"kind" -> field(record,"kind"),
              "pid" -> str(ProcessHandle.current().pid().toString)).noSpaces)
            new CountDownLatch(1).await())
      if args.head == "inspect" then
        println(store.diagnostics.noSpaces); store.close()
      else
        val password = Files.readString(Path.of(text(config,"identityPasswordFile"))).trim.toCharArray
        def ks(name: String): KeyStore =
          val result = KeyStore.getInstance("PKCS12")
          val stream = Files.newInputStream(Path.of(text(config,name)))
          try result.load(stream,password) finally stream.close()
          result
        val server = PolicyWitnessServer.start(new InetSocketAddress("127.0.0.1",9443),PolicyTls.context(ks("identityStore"),password,ks("trustStore")),
          store,installation,key,public,Map(text(config,"writerFingerprint") -> "writer",text(config,"recoveryFingerprint") -> "recovery"))
        Files.writeString(Path.of("/work/pid"),ProcessHandle.current().pid().toString)
        Files.writeString(Path.of("/work/ready"),server.port.toString)
        new CountDownLatch(1).await()
