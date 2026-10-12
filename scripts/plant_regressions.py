"""Apply/restore one planted regression bug (see verify-regressions.sh). Usage: plant_regressions.py (count|info|apply|restore) <i>."""
import json
import os
import sys

E = 'mizan-engine/src/main/java/com/sajin/mizan/engine/'
PLANTS = [
    ("defect 1", [(E + 'Sequencer.java', 'if (r.status() != ExecResult.Status.AUTH_FAILED) { // defect 1', 'if (r.status() != null) { // PLANTED')],
     ':mizan-engine:test', '*SequencerTest.authFailedCommandNeverEntersBlock'),
    ("defect 3", [(E + 'OrderBook.java', 'if (o.accountId() != taker.accountId()) {\n          sum', 'if (o.accountId() != -1L) {\n          sum')],
     ':mizan-engine:test', '*FokTest.ownRestingLiquidityDoesNotCount'),
    ("defect 4", [(E + 'ExecutionCore.java', 'if (p.type() == OrderType.MARKET && p.tif() != TimeInForce.IOC) {', 'if (p.qty() < 0) {'),
                  (E + 'ExecutionCore.java', 'if (p.tif() == TimeInForce.FOK && p.type() != OrderType.LIMIT) {', 'if (p.price() < 0) {'),
                  (E + 'Order.java', 'if (p.tif() != TimeInForce.IOC) {', 'if (p.qty() < 0) {')],
     ':mizan-engine:test', '*FokTest.marketFokRejected'),
    ("defect 5", [('mizan-sim/src/main/java/com/sajin/mizan/sim/AuditTap.java',
                   '    if (!queue.offer(t)) {\n      dropped.incrementAndGet();\n    }',
                   '    try {\n      out.write(format(t).getBytes(StandardCharsets.US_ASCII));\n    } catch (IOException e) {\n      dropped.incrementAndGet();\n    }')],
     ':mizan-sim:test', '*AuditTapTest.stalledReaderNeverBlocksEngine'),
    ("defect 6", [(E + 'Sequencer.java', 'listener.onRevoke(from); // defect 6', 'listener.onRevoke(Math.min(from, 0L)); // PLANTED')],
     ':mizan-node:test', '*PersistentNodeTest.revokeStartsAtOldestUncommittedTrade'),
    ("defect 8", [('mizan-net/src/main/java/com/sajin/mizan/net/TcpGateway.java', '      if (cached != null) {', '      if (cached != null && account < 0) {')],
     ':mizan-net:test', '*GatewayTest.retryAfterLostAckReturnsOriginalResult'),
    ("defect 9", [(E + 'Ledger.java', '? Math.multiplyExact(o.limitPrice(), o.remaining())', '? o.limitPrice() * o.remaining()')],
     ':mizan-engine:test', '*ArchRulesTest.noRawMultiplyOnMoney'),
    ("hash iteration", [(E + 'OrderBook.java', '    return index.size();', '    int n = 0;\n    for (Order o : index.values()) {\n      if (o.remaining() > 0) {\n        n++;\n      }\n    }\n    return n;')],
     ':mizan-engine:test', '*ArchRulesTest.noHashOrderedIteration'),
    ("defect 1 (block)", [('mizan-chain/src/main/java/com/sajin/mizan/chain/BlockValidator.java', 'if (r.status() == ExecResult.Status.AUTH_FAILED) {', 'if (r.status() == null) {')],
     ':mizan-chain:test', '*BlockValidityTest.authFailedInBlockIsInvalid'),
    ("defect 2", [('mizan-chain/src/main/java/com/sajin/mizan/chain/BlockValidator.java', 'if (mode == Mode.LIVE && Math.abs(h.timestampMillis() - nowMillis) > MAX_CLOCK_SKEW_MS) {', 'if (mode != null && Math.abs(h.timestampMillis() - nowMillis) > MAX_CLOCK_SKEW_MS) {')],
     ':mizan-chain:test', '*BlockValidityTest.oldBlockValidDuringSync'),
    ("clock", [(E + 'Ledger.java', '  public long total(int asset) {\n', '  public long total(int asset) {\n    if (System.nanoTime() == 42L) {\n      return 0;\n    }\n')],
     ':mizan-engine:test', '*ArchRulesTest.noClock'),
    ("peer auth", [('mizan-net/src/main/java/com/sajin/mizan/net/TcpNetwork.java', 'if (!auth.verify(from, transcript(me, from, challenge), sig)) {', 'if (sig == null) {')],
     ':mizan-node:test', '*PeerAuthTest.onlyKeyHoldersCanSpeakForAValidator'),
    ("md subscriber cap", [('mizan-net/src/main/java/com/sajin/mizan/net/md/MarketDataService.java', 'subscribers.containsKey(from) || subscribers.size() < maxSubscribers', 'subscribers.containsKey(from) || maxSubscribers > 0')],
     ':mizan-net:test', '*SubscriberCapTest.subscriptionsAreCapped'),
]

BACKUP = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'build', 'plant-backup.json')


def main():
    mode = sys.argv[1]
    if mode == 'count':
        print(len(PLANTS))
        return
    name, edits, task, test = PLANTS[int(sys.argv[2])]
    if mode == 'info':
        print(name + '|' + task + '|' + test)
    elif mode == 'apply':
        backups = {}
        for f, old, new in edits:
            if f not in backups:
                backups[f] = open(f, encoding='utf-8').read()
            s = open(f, encoding='utf-8').read()
            assert old in s, (name, f, old)
            open(f, 'w', encoding='utf-8', newline='\n').write(s.replace(old, new, 1))
        json.dump(backups, open(BACKUP, 'w', encoding='utf-8'))
    elif mode == 'restore':
        for f, s in json.load(open(BACKUP, encoding='utf-8')).items():
            open(f, 'w', encoding='utf-8', newline='\n').write(s)


main()
