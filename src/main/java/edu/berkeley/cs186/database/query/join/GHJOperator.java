package edu.berkeley.cs186.database.query.join;

import edu.berkeley.cs186.database.TransactionContext;
import edu.berkeley.cs186.database.common.HashFunc;
import edu.berkeley.cs186.database.common.Pair;
import edu.berkeley.cs186.database.common.iterator.BacktrackingIterator;
import edu.berkeley.cs186.database.databox.DataBox;
import edu.berkeley.cs186.database.query.JoinOperator;
import edu.berkeley.cs186.database.query.QueryOperator;
import edu.berkeley.cs186.database.query.disk.Partition;
import edu.berkeley.cs186.database.query.disk.Run;
import edu.berkeley.cs186.database.table.Record;
import edu.berkeley.cs186.database.table.Schema;

import java.util.*;

public class GHJOperator extends JoinOperator {
    private int numBuffers;
    private Run joinedRecords;

    public GHJOperator(QueryOperator leftSource,
                       QueryOperator rightSource,
                       String leftColumnName,
                       String rightColumnName,
                       TransactionContext transaction) {
        super(leftSource, rightSource, leftColumnName, rightColumnName, transaction, JoinType.GHJ);
        this.numBuffers = transaction.getWorkMemSize();
        this.stats = this.estimateStats();
        this.joinedRecords = null;
    }

    @Override
    public int estimateIOCost() {
        // Since this has a chance of failing on certain inputs we give it the
        // maximum possible cost to encourage the optimizer to avoid it
        return Integer.MAX_VALUE;
    }

    @Override
    public boolean materialized() {
        return true;
    }

    @Override
    public BacktrackingIterator<Record> backtrackingIterator() {
        if (joinedRecords == null) {
            // Executing GHJ on-the-fly is arduous without coroutines, so
            // instead we'll accumulate all of our joined records in this run
            // and return an iterator over it once the algorithm completes
            this.joinedRecords = new Run(getTransaction(), getSchema());
            this.run(getLeftSource(), getRightSource(), 1);
        }
        ;
        return joinedRecords.iterator();
    }

    @Override
    public Iterator<Record> iterator() {
        return backtrackingIterator();
    }

    /**
     * For every record in the given iterator, hashes the value
     * at the column we're joining on and adds it to the correct partition in
     * partitions.
     *
     * @param partitions an array of partitions to split the records into
     * @param records    iterable of records we want to partition
     * @param left       true if records are from the left relation, otherwise false
     * @param pass       the current pass (used to pick a hash function)
     */
    private void partition(Partition[] partitions, Iterable<Record> records, boolean left, int pass) {
        // TODO(proj3_part1): implement the partitioning logic Done
        // You may find the implementation in SHJOperator.java to be a good
        // starting point. You can use the static method HashFunc.hashDataBox
        // to get a hash value.
        for (Record record : records) {
            DataBox columnValue;

            if (left) columnValue = record.getValue(getLeftColumnIndex());
            else columnValue = record.getValue(getRightColumnIndex());

            int hash = HashFunc.hashDataBox(columnValue, pass);

            // modulo
            int partitionNum = hash % partitions.length;
            if (partitionNum < 0) {
                partitionNum += partitions.length;
            }
            partitions[partitionNum].add((record));
        }
    }

    /**
     * Runs the buildAndProbe stage on a given partition. Should add any
     * matching records found during the probing stage to this.joinedRecords.
     */
    private void buildAndProbe(Partition leftPartition, Partition rightPartition) {
        // true if the probe records come from the left partition, false otherwise
        boolean probeFirst;
        // We'll build our in memory hash table with these records
        Iterable<Record> buildRecords;
        // We'll probe the table with these records
        Iterable<Record> probeRecords;
        // The index of the join column for the build records
        int buildColumnIndex;
        // The index of the join column for the probe records
        int probeColumnIndex;

        // build , prove 테이블을 결정 : 메모리에 모두 로드할 수 있는 테이블이 build (작은) 테이블이 됨
        if (fitsInMemory(leftPartition)) {
            buildRecords = leftPartition;
            buildColumnIndex = getLeftColumnIndex();
            probeRecords = rightPartition;
            probeColumnIndex = getRightColumnIndex();
            probeFirst = false;
        } else if (fitsInMemory(rightPartition)) {
            buildRecords = rightPartition;
            buildColumnIndex = getRightColumnIndex();
            probeRecords = leftPartition;
            probeColumnIndex = getLeftColumnIndex();
            probeFirst = true;
        } else {
            throw new IllegalArgumentException(
                    "Neither the left nor the right records in this partition " +
                            "fit in B-2 pages of memory."
            );
        }
        // TODO(proj3_part1): implement the building and probing stage Done
        // You shouldn't refer to any variable starting with "left" or "right"
        // here, use the "build" and "probe" variables we set up for you.
        // Check out how SHJOperator implements this function if you feel stuck.

        // Building stage : SHJ 와 동일
        Map<DataBox, List<Record>> hashTable = new HashMap<>();

        for (Record record : buildRecords) {
            DataBox buildJoinValue = record.getValue(buildColumnIndex);
            if (!hashTable.containsKey(buildJoinValue)) {
                hashTable.put(buildJoinValue, new ArrayList<>());
            }
            hashTable.get(buildJoinValue).add(record);
        }

        // Probing stage : prove 테이블이 left 인지 확인 핋요
        for (Record probeRecord : probeRecords) {
            DataBox probeJoinValue = probeRecord.getValue(probeColumnIndex);
            if (!hashTable.containsKey(probeJoinValue)) continue;

            for (Record buildRecord : hashTable.get(probeJoinValue)) {
                Record concatRecord = getConcatRecord(probeFirst, probeRecord, buildRecord);
                this.joinedRecords.add(concatRecord);
            }
        }
    }

    private boolean fitsInMemory(Partition partition) {
        return partition.getNumPages() <= this.numBuffers - 2;
    }

    private Record getConcatRecord(boolean probeFirst, Record probeRecord, Record buildRecord) {
        return probeFirst ? probeRecord.concat(buildRecord) : buildRecord.concat(probeRecord);
    }

    /**
     * Runs the grace hash join algorithm. Each pass starts by partitioning
     * leftRecords and rightRecords. If we can run build and probe on a
     * partition we should immediately do so, otherwise we should apply the
     * grace hash join algorithm recursively to break up the partitions further.
     */
    private void run(Iterable<Record> leftRecords, Iterable<Record> rightRecords, int pass) {
        assert pass >= 1;
        if (pass > 5) throw new IllegalStateException("Reached the max number of passes");

        // Create empty partitions
        Partition[] leftPartitions = createPartitions(true);
        Partition[] rightPartitions = createPartitions(false);

        // Partition records into left and right
        this.partition(leftPartitions, leftRecords, true, pass);
        this.partition(rightPartitions, rightRecords, false, pass);

        for (int i = 0; i < leftPartitions.length; i++) {
            // TODO(proj3_part1): implement the rest of grace hash join Done

            if (canBuildAndProbe(leftPartitions, rightPartitions, i)) {
                // If you meet the conditions to run the build and probe you should do so immediately.
                buildAndProbe(leftPartitions[i], rightPartitions[i]);
            } else {
                //  Otherwise you should make a recursive call. 다음 단계로
                run(leftPartitions[i], rightPartitions[i], pass + 1);
            }
        }
    }

    private boolean canBuildAndProbe(Partition[] leftPartitions, Partition[] rightPartitions, int i) {
        return fitsInMemory(leftPartitions[i]) || fitsInMemory(rightPartitions[i]);
    }

    // Provided Helpers ////////////////////////////////////////////////////////

    /**
     * Create an appropriate number of partitions relative to the number of
     * available buffers we have.
     *
     * @return an array of partitions
     */
    private Partition[] createPartitions(boolean left) {
        int usableBuffers = this.numBuffers - 1;
        Partition partitions[] = new Partition[usableBuffers];
        for (int i = 0; i < usableBuffers; i++) {
            partitions[i] = createPartition(left);
        }
        return partitions;
    }

    /**
     * Creates either a regular partition or a smart partition depending on the
     * value of this.useSmartPartition.
     *
     * @param left true if this partition will store records from the left
     *             relation, false otherwise
     * @return a partition to store records from the specified partition
     */
    private Partition createPartition(boolean left) {
        Schema schema = getRightSource().getSchema();
        if (left) schema = getLeftSource().getSchema();
        return new Partition(getTransaction(), schema);
    }

    // Student Input Methods ///////////////////////////////////////////////////

    /**
     * Creates a record using val as the value for a single column of type int.
     * An extra column containing a 500 byte string is appended so that each
     * page will hold exactly 8 records.
     *
     * @param val value the field will take
     * @return a record
     */
    private static Record createRecord(int val) {
        String s = new String(new char[500]);
        return new Record(val, s);
    }

    /**
     * This method is called in testBreakSHJButPassGHJ.
     *
     * Come up with two lists of records for leftRecords and rightRecords such
     * that SHJ will error when given those relations, but GHJ will successfully
     * run. createRecord(int val) takes in an integer value and returns a record
     * with that value in the column being joined on.
     *
     * Hints: Both joins will have access to B=6 buffers and each page can fit
     * exactly 8 records.
     *
     * @return Pair of leftRecords and rightRecords
     */
    public static Pair<List<Record>, List<Record>> getBreakSHJInputs() {
        ArrayList<Record> leftRecords = new ArrayList<>();
        ArrayList<Record> rightRecords = new ArrayList<>();

        // TODO(proj3_part1): populate leftRecords and rightRecords such that Pass
        // SHJ breaks when trying to join them but not GHJ

        // SHJ 는 실패하지만 GHJ 는 성공하는 경우 ??????
        for (int i = 0; i < 200; i++) {
            leftRecords.add(createRecord(i));
            rightRecords.add(createRecord(i));
        }
        return new Pair<>(leftRecords, rightRecords);
    }

    /**
     * This method is called in testGHJBreak.
     *
     * Come up with two lists of records for leftRecords and rightRecords such
     * that GHJ will error (in our case hit the maximum number of passes).
     * createRecord(int val) takes in an integer value and returns a record
     * with that value in the column being joined on.
     *
     * Hints: Both joins will have access to B=6 buffers and each page can fit
     * exactly 8 records.
     *
     * @return Pair of leftRecords and rightRecords
     */
    public static Pair<List<Record>, List<Record>> getBreakGHJInputs() {
        ArrayList<Record> leftRecords = new ArrayList<>();
        ArrayList<Record> rightRecords = new ArrayList<>();
        // TODO(proj3_part1): populate leftRecords and rightRecords such that GHJ breaks Done

        // (6 - 2) * 8 = 32
        for (int i = 0; i < 33; i++) {
            leftRecords.add(createRecord(0));
            rightRecords.add(createRecord(0));
        }
        return new Pair<>(leftRecords, rightRecords);
    }
}

