package org.deeplearning4j.spark.canova;

import org.apache.spark.api.java.function.Function;
import org.canova.api.io.WritableConverter;
import org.canova.api.writable.Writable;
import org.deeplearning4j.datasets.canova.SequenceRecordReaderDataSetIterator;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.dataset.api.DataSet;
import org.nd4j.linalg.dataset.api.DataSetPreProcessor;
import org.nd4j.linalg.factory.Nd4j;
import org.nd4j.linalg.indexing.NDArrayIndex;
import org.nd4j.linalg.util.FeatureUtil;
import scala.Tuple2;

import java.io.Serializable;
import java.util.Collection;
import java.util.Iterator;

/**Map {@code Tuple2<Collection<Collection<Writable>>,Collection<Collection<Writable>>} objects (out of a TWO canova-spark
 *  sequence record reader functions) to  DataSet objects for Spark training.
 * Analogous to {@link SequenceRecordReaderDataSetIterator}, but in the context of Spark.
 * Supports loading data from a TWO sources only; hence supports many-to-one and one-to-many situations.
 * see {@link CanovaSequenceDataSetFunction} for the single file version
 * @author Alex Black
 */
public class CanovaTwoSequenceDataSetFunction implements Function<Tuple2<Collection<Collection<Writable>>,Collection<Collection<Writable>>>,DataSet>, Serializable {
    /**Alignment mode for dealing with input/labels of differing lengths (for example, one-to-many and many-to-one type situations).
     * For example, might have 10 time steps total but only one label at end for sequence classification.<br>
     * Currently supported modes:<br>
     * <b>EQUAL_LENGTH</b>: Default. Assume that label and input time series are of equal length, and all examples are of
     * the same length<br>
     * <b>ALIGN_START</b>: Align the label/input time series at the first time step, and zero pad either the labels or
     * the input at the end<br>
     * <b>ALIGN_END</b>: Align the label/input at the last time step, zero padding either the input or the labels as required<br>
     *
     * Note 1: When the time series for each example are of different lengths, the shorter time series will be padded to
     * the length of the longest time series.<br>
     * Note 2: When ALIGN_START or ALIGN_END are used, the DataSet masking functionality is used. Thus, the returned DataSets
     * will have the input and mask arrays set. These mask arrays identify whether an input/label is actually present,
     * or whether the value is merely masked.<br>
     */
    public enum AlignmentMode {
        EQUAL_LENGTH,
        ALIGN_START,
        ALIGN_END
    }

    private final boolean regression;
    private final int numPossibleLabels;
    private final AlignmentMode alignmentMode;
    private final DataSetPreProcessor preProcessor;
    private final WritableConverter converter;

    /**Constructor for equal length, no data set preprocessor or writable converter
     * @see #CanovaTwoSequenceDataSetFunction(int, boolean, AlignmentMode, DataSetPreProcessor, WritableConverter)
     */
    public CanovaTwoSequenceDataSetFunction(int numPossibleLabels, boolean regression){
        this(numPossibleLabels, regression, AlignmentMode.EQUAL_LENGTH);
    }

    /**Constructor for data with a specified alignment mode, no data set preprocessor or writable converter
     * @see #CanovaTwoSequenceDataSetFunction(int, boolean, AlignmentMode, DataSetPreProcessor, WritableConverter)
     */
    public CanovaTwoSequenceDataSetFunction(int numPossibleLabels, boolean regression, AlignmentMode alignmentMode){
        this(numPossibleLabels, regression, alignmentMode, null, null);
    }

    /**
     * @param numPossibleLabels Number of classes for classification  (not used if regression = true)
     * @param regression False for classification, true for regression
     * @param alignmentMode Alignment mode for data. See {@link org.deeplearning4j.spark.canova.CanovaTwoSequenceDataSetFunction.AlignmentMode}
     * @param preProcessor DataSetPreprocessor (may be null)
     * @param converter WritableConverter (may be null)
     */
    public CanovaTwoSequenceDataSetFunction(int numPossibleLabels, boolean regression,
                                            AlignmentMode alignmentMode, DataSetPreProcessor preProcessor,
                                            WritableConverter converter){
        this.numPossibleLabels = numPossibleLabels;
        this.regression = regression;
        this.alignmentMode = alignmentMode;
        this.preProcessor = preProcessor;
        this.converter = converter;
    }


    @Override
    public DataSet call(Tuple2<Collection<Collection<Writable>>,Collection<Collection<Writable>>> input) throws Exception {
        Collection<Collection<Writable>> featuresSeq = input._1();
        Collection<Collection<Writable>> labelsSeq = input._2();

        int featuresLength = featuresSeq.size();
        int labelsLength = labelsSeq.size();


        Iterator<Collection<Writable>> fIter = featuresSeq.iterator();
        Iterator<Collection<Writable>> lIter = labelsSeq.iterator();

        INDArray inputArr = null;
        INDArray outputArr = null;

        int[] idx = new int[3];
        int i = 0;
        while(fIter.hasNext()){
            Collection<Writable> step = fIter.next();
            if (i == 0) {
                int[] inShape = new int[]{1,step.size(),featuresLength};
                inputArr = Nd4j.create(inShape);
            }
            Iterator<Writable> timeStepIter = step.iterator();
            int f = 0;
            while (timeStepIter.hasNext()) {
                Writable current = timeStepIter.next();
                if(converter != null) current = converter.convert(current);
                idx[1] = f++;
                inputArr.putScalar(idx, current.toDouble());
            }
            idx[2] = i++;
        }

        idx = new int[3];
        i = 0;
        while(lIter.hasNext()){
            Collection<Writable> step = lIter.next();
            if (i == 0) {
                int[] outShape = new int[]{1,(regression ? step.size() : numPossibleLabels),labelsLength};
                outputArr = Nd4j.create(outShape);
            }
            Iterator<Writable> timeStepIter = step.iterator();
            int f = 0;
            while (timeStepIter.hasNext()) {
                Writable current = timeStepIter.next();
                if(converter != null) current = converter.convert(current);
                idx[1] = f++;
                outputArr.putScalar(idx, current.toDouble());
            }
            idx[2] = i++;
        }

        DataSet ds;
        if(alignmentMode == AlignmentMode.EQUAL_LENGTH || featuresLength == labelsLength){
            ds = new org.nd4j.linalg.dataset.DataSet(inputArr,outputArr);
        } else if(alignmentMode == AlignmentMode.ALIGN_END){
            if(featuresLength > labelsLength ){
                //Input longer, pad output
                INDArray newOutput = Nd4j.create(1,outputArr.size(1),featuresLength);
                newOutput.get(NDArrayIndex.point(1),NDArrayIndex.all(), NDArrayIndex.interval(featuresLength-labelsLength,featuresLength))
                        .assign(outputArr);
                //Need an output mask array, but not an input mask array
                INDArray outputMask = Nd4j.create(1,featuresLength);
                for( int j=featuresLength-labelsLength; j<featuresLength; j++ ) outputMask.putScalar(j,1.0);
                ds = new org.nd4j.linalg.dataset.DataSet(inputArr,newOutput,null,outputMask);
            } else {
                //Output longer, pad input
                INDArray newInput = Nd4j.create(1,inputArr.size(1),labelsLength);
                newInput.get(NDArrayIndex.point(1),NDArrayIndex.all(), NDArrayIndex.interval(labelsLength-featuresLength,labelsLength))
                        .assign(inputArr);
                //Need an input mask array, but not an output mask array
                INDArray inputMask = Nd4j.create(1,labelsLength);
                for( int j=labelsLength-featuresLength; j<labelsLength; j++ ) inputMask.putScalar(j,1.0);
                ds = new org.nd4j.linalg.dataset.DataSet(newInput,outputArr,inputMask,null);
            }
        } else if(alignmentMode == AlignmentMode.ALIGN_START){
            if(featuresLength > labelsLength ){
                //Input longer, pad output
                INDArray newOutput = Nd4j.create(1,outputArr.size(1),featuresLength);
                newOutput.get(NDArrayIndex.point(1),NDArrayIndex.all(), NDArrayIndex.interval(0,labelsLength)).assign(outputArr);
                //Need an output mask array, but not an input mask array
                INDArray outputMask = Nd4j.create(1,featuresLength);
                for( int j=0; j<labelsLength; j++ ) outputMask.putScalar(j,1.0);
                ds = new org.nd4j.linalg.dataset.DataSet(inputArr,newOutput,null,outputMask);
            } else {
                //Output longer, pad input
                INDArray newInput = Nd4j.create(1,inputArr.size(1),labelsLength);
                newInput.get(NDArrayIndex.point(1),NDArrayIndex.all(), NDArrayIndex.interval(0,featuresLength)).assign(inputArr);
                //Need an input mask array, but not an output mask array
                INDArray inputMask = Nd4j.create(1,labelsLength);
                for( int j=0; j<featuresLength; j++ ) inputMask.putScalar(j,1.0);
                ds = new org.nd4j.linalg.dataset.DataSet(newInput,outputArr,inputMask,null);
            }
        } else {
            throw new UnsupportedOperationException("Invalid alignment mode: " + alignmentMode);
        }


        if(preProcessor != null) preProcessor.preProcess(ds);
        return ds;
    }
}
