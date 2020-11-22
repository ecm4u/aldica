/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package org.aldica.repo.ignite.binary.value;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.aldica.common.ignite.GridTestsBase;
import org.aldica.repo.ignite.ExpensiveTestCategory;
import org.aldica.repo.ignite.cache.NodeAspectsCacheSet;
import org.alfresco.model.ContentModel;
import org.alfresco.repo.domain.qname.QNameDAO;
import org.alfresco.repo.domain.qname.ibatis.QNameDAOImpl;
import org.alfresco.service.namespace.QName;
import org.alfresco.util.Pair;
import org.apache.ignite.DataRegionMetrics;
import org.apache.ignite.Ignite;
import org.apache.ignite.IgniteCache;
import org.apache.ignite.Ignition;
import org.apache.ignite.binary.BinaryTypeConfiguration;
import org.apache.ignite.cache.CacheMode;
import org.apache.ignite.configuration.BinaryConfiguration;
import org.apache.ignite.configuration.CacheConfiguration;
import org.apache.ignite.configuration.DataPageEvictionMode;
import org.apache.ignite.configuration.DataRegionConfiguration;
import org.apache.ignite.configuration.DataStorageConfiguration;
import org.apache.ignite.configuration.IgniteConfiguration;
import org.easymock.EasyMock;
import org.junit.Assert;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.support.GenericApplicationContext;

/**
 * @author Axel Faust
 */
public class NodeAspectsBinarySerializerTests extends GridTestsBase
{

    private static final QName[] ASPECT_QNAMES = { ContentModel.ASPECT_REFERENCEABLE, ContentModel.ASPECT_AUDITABLE,
            ContentModel.ASPECT_ARCHIVED, ContentModel.ASPECT_AUTHOR, ContentModel.ASPECT_CLASSIFIABLE, ContentModel.ASPECT_CHECKED_OUT,
            ContentModel.ASPECT_UNDELETABLE, ContentModel.ASPECT_UNMOVABLE, ContentModel.ASPECT_LOCKABLE, ContentModel.ASPECT_HIDDEN };

    private static final Logger LOGGER = LoggerFactory.getLogger(NodeAspectsBinarySerializerTests.class);

    protected static GenericApplicationContext createApplicationContext()
    {
        final GenericApplicationContext appContext = new GenericApplicationContext();

        final QNameDAO qnameDAO = EasyMock.partialMockBuilder(QNameDAOImpl.class).addMockedMethod("getQName", Long.class)
                .addMockedMethod("getQName", QName.class).createMock();
        appContext.getBeanFactory().registerSingleton("qnameDAO", qnameDAO);
        appContext.refresh();

        for (int idx = 0; idx < ASPECT_QNAMES.length; idx++)
        {
            EasyMock.expect(qnameDAO.getQName(Long.valueOf(idx))).andStubReturn(new Pair<>(Long.valueOf(idx), ASPECT_QNAMES[idx]));
            EasyMock.expect(qnameDAO.getQName(ASPECT_QNAMES[idx])).andStubReturn(new Pair<>(Long.valueOf(idx), ASPECT_QNAMES[idx]));
        }
        EasyMock.expect(qnameDAO.getQName(EasyMock.anyObject(QName.class))).andStubReturn(null);

        EasyMock.replay(qnameDAO);

        return appContext;
    }

    protected static IgniteConfiguration createConfiguration(final ApplicationContext applicationContext, final boolean idsWhenReasonable,
            final boolean serialForm, final String... regionNames)
    {
        final IgniteConfiguration conf = createConfiguration(1, false, null);

        final BinaryConfiguration binaryConfiguration = new BinaryConfiguration();

        final NodeAspectsBinarySerializer serializer = new NodeAspectsBinarySerializer();
        serializer.setApplicationContext(applicationContext);
        serializer.setUseIdsWhenReasonable(idsWhenReasonable);
        serializer.setUseRawSerialForm(serialForm);
        serializer.setUseVariableLengthIntegers(serialForm);

        final BinaryTypeConfiguration binaryTypeConfigurationForNodeAspectsCacheSet = new BinaryTypeConfiguration();
        binaryTypeConfigurationForNodeAspectsCacheSet.setTypeName(NodeAspectsCacheSet.class.getName());
        binaryTypeConfigurationForNodeAspectsCacheSet.setSerializer(serializer);

        binaryConfiguration.setTypeConfigurations(Arrays.asList(binaryTypeConfigurationForNodeAspectsCacheSet));
        conf.setBinaryConfiguration(binaryConfiguration);

        final DataStorageConfiguration dataConf = new DataStorageConfiguration();
        final List<DataRegionConfiguration> regionConfs = new ArrayList<>();
        for (final String regionName : regionNames)
        {
            final DataRegionConfiguration regionConf = new DataRegionConfiguration();
            regionConf.setName(regionName);
            // all regions are 10-250 MiB
            regionConf.setInitialSize(10 * 1024 * 1024);
            regionConf.setMaxSize(250 * 1024 * 1024);
            regionConf.setPageEvictionMode(DataPageEvictionMode.RANDOM_2_LRU);
            regionConf.setMetricsEnabled(true);
            regionConfs.add(regionConf);
        }
        dataConf.setDataRegionConfigurations(regionConfs.toArray(new DataRegionConfiguration[0]));
        conf.setDataStorageConfiguration(dataConf);

        return conf;
    }

    @Test
    public void defaultFormCorrectness()
    {
        final IgniteConfiguration conf = createConfiguration(null, false, false);
        this.correctnessImpl(conf);
    }

    @Test
    public void defaultFormQNameIdSubstitutionCorrectness()
    {
        try (final GenericApplicationContext appContext = createApplicationContext())
        {
            final IgniteConfiguration conf = createConfiguration(appContext, true, false);
            this.correctnessImpl(conf);
        }
    }

    @Category(ExpensiveTestCategory.class)
    @Test
    public void defaultFormEfficiency()
    {
        try (final GenericApplicationContext appContext = createApplicationContext())
        {
            final IgniteConfiguration referenceConf = createConfiguration(1, false, null);
            referenceConf.setIgniteInstanceName(referenceConf.getIgniteInstanceName() + "-reference");

            final IgniteConfiguration defaultConf = createConfiguration(null, false, false, "comparison1", "comparison2", "comparison3");
            final IgniteConfiguration useQNameIdConf = createConfiguration(appContext, true, false, "comparison1", "comparison2",
                    "comparison3");

            useQNameIdConf.setIgniteInstanceName(useQNameIdConf.getIgniteInstanceName() + "-qnameIdSubstitution");

            referenceConf.setDataStorageConfiguration(defaultConf.getDataStorageConfiguration());

            try
            {
                final Ignite referenceGrid = Ignition.start(referenceConf);
                final Ignite defaultGrid = Ignition.start(defaultConf);
                final Ignite useQNameIdGrid = Ignition.start(useQNameIdConf);

                final CacheConfiguration<Long, NodeAspectsCacheSet> cacheConfig = new CacheConfiguration<>();
                cacheConfig.setCacheMode(CacheMode.LOCAL);

                cacheConfig.setName("comparison1");
                cacheConfig.setDataRegionName("comparison1");
                final IgniteCache<Long, NodeAspectsCacheSet> referenceCache1 = referenceGrid.getOrCreateCache(cacheConfig);
                final IgniteCache<Long, NodeAspectsCacheSet> cache1 = defaultGrid.getOrCreateCache(cacheConfig);

                // default uses HashSet.writeObject and Serializable all the way through, which is already very efficient
                // this actually intrinsically deduplicates common objects / values (e.g. namespace URIs)
                // without ID substitution (or our custom QNameBinarySerializer), our serialisation cannot come close - -94%
                this.efficiencyImpl(referenceGrid, defaultGrid, referenceCache1, cache1, "aldica optimised", "Ignite default", -0.94);

                cacheConfig.setName("comparison2");
                cacheConfig.setDataRegionName("comparison2");
                final IgniteCache<Long, NodeAspectsCacheSet> referenceCache2 = referenceGrid.getOrCreateCache(cacheConfig);
                final IgniteCache<Long, NodeAspectsCacheSet> cache2 = useQNameIdGrid.getOrCreateCache(cacheConfig);

                // ID substitution is everything - 57%
                this.efficiencyImpl(referenceGrid, useQNameIdGrid, referenceCache2, cache2, "aldica optimised (QName ID substitution)",
                        "Ignite default", 0.57);

                cacheConfig.setName("comparison3");
                cacheConfig.setDataRegionName("comparison3");
                final IgniteCache<Long, NodeAspectsCacheSet> referenceCache3 = defaultGrid.getOrCreateCache(cacheConfig);
                final IgniteCache<Long, NodeAspectsCacheSet> cache3 = useQNameIdGrid.getOrCreateCache(cacheConfig);

                // ID substitution is everything - 78%
                this.efficiencyImpl(defaultGrid, useQNameIdGrid, referenceCache3, cache3, "aldica optimised (QName ID substitution)",
                        "aldica optimised", 0.78);
            }
            finally
            {
                Ignition.stopAll(true);
            }
        }
    }

    @Test
    public void rawSerialFormCorrectness()
    {
        final IgniteConfiguration conf = createConfiguration(null, false, true);
        this.correctnessImpl(conf);
    }

    @Test
    public void rawSerialFormQNameIdSubstitutionCorrectness()
    {
        try (final GenericApplicationContext appContext = createApplicationContext())
        {
            final IgniteConfiguration conf = createConfiguration(appContext, true, true);
            this.correctnessImpl(conf);
        }
    }

    @Category(ExpensiveTestCategory.class)
    @Test
    public void rawSerialFormEfficiency()
    {
        try (final GenericApplicationContext appContext = createApplicationContext())
        {
            final IgniteConfiguration referenceConf = createConfiguration(null, false, false, "comparison1", "comparison2", "comparison3");
            referenceConf.setIgniteInstanceName(referenceConf.getIgniteInstanceName() + "-reference");

            final IgniteConfiguration defaultConf = createConfiguration(null, false, true, "comparison1", "comparison2", "comparison3");
            final IgniteConfiguration useQNameIdConf = createConfiguration(appContext, true, true, "comparison1", "comparison2",
                    "comparison3");

            useQNameIdConf.setIgniteInstanceName(useQNameIdConf.getIgniteInstanceName() + "-qnameIdSubstitution");

            try
            {
                final Ignite referenceGrid = Ignition.start(referenceConf);
                final Ignite defaultGrid = Ignition.start(defaultConf);
                final Ignite useQNameIdGrid = Ignition.start(useQNameIdConf);

                final CacheConfiguration<Long, NodeAspectsCacheSet> cacheConfig = new CacheConfiguration<>();
                cacheConfig.setCacheMode(CacheMode.LOCAL);

                cacheConfig.setName("comparison1");
                cacheConfig.setDataRegionName("comparison1");
                final IgniteCache<Long, NodeAspectsCacheSet> referenceCache1 = referenceGrid.getOrCreateCache(cacheConfig);
                final IgniteCache<Long, NodeAspectsCacheSet> cache1 = defaultGrid.getOrCreateCache(cacheConfig);

                // only a slight improvement due to metadata + collection handling - 0.9%
                this.efficiencyImpl(referenceGrid, defaultGrid, referenceCache1, cache1, "aldica raw serial", "aldica optimised", 0.009);

                cacheConfig.setName("comparison2");
                cacheConfig.setDataRegionName("comparison2");
                final IgniteCache<Long, NodeAspectsCacheSet> referenceCache2 = referenceGrid.getOrCreateCache(cacheConfig);
                final IgniteCache<Long, NodeAspectsCacheSet> cache2 = useQNameIdGrid.getOrCreateCache(cacheConfig);

                // ID substitution + variable length integers are critical - 84%
                this.efficiencyImpl(referenceGrid, useQNameIdGrid, referenceCache2, cache2, "aldica raw serial (ID substitution)",
                        "aldica optimised", 0.84);

                cacheConfig.setName("comparison3");
                cacheConfig.setDataRegionName("comparison3");
                final IgniteCache<Long, NodeAspectsCacheSet> referenceCache3 = defaultGrid.getOrCreateCache(cacheConfig);
                final IgniteCache<Long, NodeAspectsCacheSet> cache3 = useQNameIdGrid.getOrCreateCache(cacheConfig);

                // ID substitution is everything - 84%
                this.efficiencyImpl(defaultGrid, useQNameIdGrid, referenceCache3, cache3, "aldica raw serial (ID substitution)",
                        "aldica raw serial", 0.84);
            }
            finally
            {
                Ignition.stopAll(true);
            }
        }
    }

    protected void correctnessImpl(final IgniteConfiguration conf)
    {
        try (Ignite grid = Ignition.start(conf))
        {
            final CacheConfiguration<Long, NodeAspectsCacheSet> cacheConfig = new CacheConfiguration<>();
            cacheConfig.setName("contentData");
            cacheConfig.setCacheMode(CacheMode.LOCAL);
            final IgniteCache<Long, NodeAspectsCacheSet> cache = grid.getOrCreateCache(cacheConfig);

            NodeAspectsCacheSet controlValue;
            NodeAspectsCacheSet cacheValue;

            controlValue = new NodeAspectsCacheSet();
            for (final QName aspectQName : ASPECT_QNAMES)
            {
                controlValue.add(aspectQName);
            }

            cache.put(1l, controlValue);
            cacheValue = cache.get(1l);

            Assert.assertEquals(controlValue, cacheValue);
            // check deep serialisation was actually involved (different value instances)
            Assert.assertNotSame(controlValue, cacheValue);
        }
    }

    protected void efficiencyImpl(final Ignite referenceGrid, final Ignite defaultGrid,
            final IgniteCache<Long, NodeAspectsCacheSet> referenceCache, final IgniteCache<Long, NodeAspectsCacheSet> cache,
            final String serialisationType, final String referenceSerialisationType, final double marginFraction)
    {
        LOGGER.info(
                "Running NodeAspectsCacheSet serialisation benchmark of 100k instances, comparing {} vs. {} serialisation, expecting relative improvement margin / difference fraction of {}",
                referenceSerialisationType, serialisationType, marginFraction);

        final SecureRandom rnJesus = new SecureRandom();
        for (int idx = 0; idx < 100000; idx++)
        {
            final NodeAspectsCacheSet value = new NodeAspectsCacheSet();

            final int countAspects = (ASPECT_QNAMES.length / 2) + rnJesus.nextInt(ASPECT_QNAMES.length / 2);
            while (value.size() < countAspects)
            {
                value.add(ASPECT_QNAMES[rnJesus.nextInt(ASPECT_QNAMES.length)]);
            }

            referenceCache.put(Long.valueOf(idx), value);
            cache.put(Long.valueOf(idx), value);
        }

        @SuppressWarnings("unchecked")
        final String regionName = cache.getConfiguration(CacheConfiguration.class).getDataRegionName();
        final DataRegionMetrics referenceMetrics = referenceGrid.dataRegionMetrics(regionName);
        final DataRegionMetrics metrics = defaultGrid.dataRegionMetrics(regionName);

        // sufficient to compare used pages - byte-exact memory usage cannot be determined due to potential partial page fill
        final long referenceTotalUsedPages = referenceMetrics.getTotalUsedPages();
        final long totalUsedPages = metrics.getTotalUsedPages();
        final long allowedMax = referenceTotalUsedPages - (long) (marginFraction * referenceTotalUsedPages);
        LOGGER.info("Benchmark resulted in {} vs {} (expected max of {}) total used pages", referenceTotalUsedPages, totalUsedPages,
                allowedMax);
        Assert.assertTrue(totalUsedPages <= allowedMax);
    }
}