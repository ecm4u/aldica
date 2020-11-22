/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package org.aldica.repo.ignite.binary.support;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import org.aldica.common.ignite.GridTestsBase;
import org.aldica.repo.ignite.ExpensiveTestCategory;
import org.alfresco.repo.cache.lookup.CacheRegionKey;
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
import org.junit.Assert;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author Axel Faust
 */
public class CacheRegionKeyBinarySerializerTests extends GridTestsBase
{

    private static final Logger LOGGER = LoggerFactory.getLogger(CacheRegionKeyBinarySerializerTests.class);

    protected static IgniteConfiguration createConfiguration(final boolean serialForm, final String... regionNames)
    {
        final IgniteConfiguration conf = createConfiguration(1, false, null);

        final BinaryConfiguration binaryConfiguration = new BinaryConfiguration();

        final BinaryTypeConfiguration binaryTypeConfigurationForCacheRegionKey = new BinaryTypeConfiguration();
        binaryTypeConfigurationForCacheRegionKey.setTypeName(CacheRegionKey.class.getName());
        final CacheRegionKeyBinarySerializer serializer = new CacheRegionKeyBinarySerializer();
        serializer.setUseRawSerialForm(serialForm);
        serializer.setUseVariableLengthIntegers(serialForm);
        binaryTypeConfigurationForCacheRegionKey.setSerializer(serializer);

        binaryConfiguration.setTypeConfigurations(Arrays.asList(binaryTypeConfigurationForCacheRegionKey));
        conf.setBinaryConfiguration(binaryConfiguration);

        final DataStorageConfiguration dataConf = new DataStorageConfiguration();
        final List<DataRegionConfiguration> regionConfs = new ArrayList<>();
        for (final String regionName : regionNames)
        {
            final DataRegionConfiguration regionConf = new DataRegionConfiguration();
            regionConf.setName(regionName);
            // all regions are 10-100 MiB
            regionConf.setInitialSize(10 * 1024 * 1024);
            regionConf.setMaxSize(100 * 1024 * 1024);
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
        final IgniteConfiguration conf = createConfiguration(false);
        this.correctnessImpl(conf);
    }

    @Category(ExpensiveTestCategory.class)
    @Test
    public void defaultFormEfficiency()
    {
        final IgniteConfiguration referenceConf = createConfiguration(1, false, null);
        referenceConf.setIgniteInstanceName(referenceConf.getIgniteInstanceName() + "-reference");
        final IgniteConfiguration conf = createConfiguration(false, "values");

        referenceConf.setDataStorageConfiguration(conf.getDataStorageConfiguration());

        try
        {
            final Ignite referenceGrid = Ignition.start(referenceConf);
            final Ignite grid = Ignition.start(conf);

            final CacheConfiguration<Long, CacheRegionKey> cacheConfig = new CacheConfiguration<>();
            cacheConfig.setCacheMode(CacheMode.LOCAL);

            cacheConfig.setName("values");
            cacheConfig.setDataRegionName("values");
            final IgniteCache<Long, CacheRegionKey> referenceCache = referenceGrid.getOrCreateCache(cacheConfig);
            final IgniteCache<Long, CacheRegionKey> cache = grid.getOrCreateCache(cacheConfig);

            // saving potential is substantial, but variable depending on region
            // expect average of 18%
            this.efficiencyImpl(referenceGrid, grid, referenceCache, cache, "aldica optimised", "Ignite default", 0.18);
        }
        finally
        {
            Ignition.stopAll(true);
        }
    }

    @Test
    public void rawSerialFormCorrectness()
    {
        final IgniteConfiguration conf = createConfiguration(true);
        this.correctnessImpl(conf);
    }

    @Category(ExpensiveTestCategory.class)
    @Test
    public void rawSerialFormEfficiency()
    {
        final IgniteConfiguration referenceConf = createConfiguration(false, "values");
        referenceConf.setIgniteInstanceName(referenceConf.getIgniteInstanceName() + "-reference");
        final IgniteConfiguration conf = createConfiguration(true, "values");

        try
        {
            final Ignite referenceGrid = Ignition.start(referenceConf);
            final Ignite grid = Ignition.start(conf);

            final CacheConfiguration<Long, CacheRegionKey> cacheConfig = new CacheConfiguration<>();
            cacheConfig.setCacheMode(CacheMode.LOCAL);

            cacheConfig.setName("values");
            cacheConfig.setDataRegionName("values");
            final IgniteCache<Long, CacheRegionKey> referenceCache1 = referenceGrid.getOrCreateCache(cacheConfig);
            final IgniteCache<Long, CacheRegionKey> cache1 = grid.getOrCreateCache(cacheConfig);

            // saving potential is limited - 5%
            this.efficiencyImpl(referenceGrid, grid, referenceCache1, cache1, "aldica raw serial", "aldica optimised", 0.05);
        }
        finally
        {
            Ignition.stopAll(true);
        }
    }

    protected void correctnessImpl(final IgniteConfiguration conf)
    {
        try (Ignite grid = Ignition.start(conf))
        {
            final CacheConfiguration<Long, CacheRegionKey> cacheConfig = new CacheConfiguration<>();
            cacheConfig.setName("cacheRegionKey");
            cacheConfig.setCacheMode(CacheMode.LOCAL);
            final IgniteCache<Long, CacheRegionKey> cache = grid.getOrCreateCache(cacheConfig);

            CacheRegionKey controlValue;
            CacheRegionKey cacheValue;

            // default region + String key
            controlValue = new CacheRegionKey(CacheRegion.DEFAULT.getCacheRegionName(), "value1");
            cache.put(1l, controlValue);

            cacheValue = cache.get(1l);

            Assert.assertEquals(controlValue, cacheValue);
            // check deep serialisation was actually involved
            Assert.assertNotSame(controlValue, cacheValue);

            // default region + Long key
            controlValue = new CacheRegionKey(CacheRegion.DEFAULT.getCacheRegionName(), Long.valueOf(1234));
            cache.put(2l, controlValue);

            cacheValue = cache.get(2l);

            Assert.assertEquals(controlValue, cacheValue);
            // check deep serialisation was actually involved
            Assert.assertNotSame(controlValue, cacheValue);

            // default region + arbitrary key
            controlValue = new CacheRegionKey(CacheRegion.DEFAULT.getCacheRegionName(), Instant.now());
            cache.put(3l, controlValue);

            cacheValue = cache.get(3l);

            Assert.assertEquals(controlValue, cacheValue);
            // check deep serialisation was actually involved
            Assert.assertNotSame(controlValue, cacheValue);

            // random region + String key
            controlValue = new CacheRegionKey(UUID.randomUUID().toString(), "value2");
            cache.put(4l, controlValue);

            cacheValue = cache.get(4l);

            Assert.assertEquals(controlValue, cacheValue);
            // check deep serialisation was actually involved
            Assert.assertNotSame(controlValue, cacheValue);

            // random region + Long key
            controlValue = new CacheRegionKey(UUID.randomUUID().toString(), Long.valueOf(1234));
            cache.put(5l, controlValue);

            cacheValue = cache.get(5l);

            Assert.assertEquals(controlValue, cacheValue);
            // check deep serialisation was actually involved
            Assert.assertNotSame(controlValue, cacheValue);

            // random region + arbitrary key
            controlValue = new CacheRegionKey(UUID.randomUUID().toString(), Instant.now());
            cache.put(6l, controlValue);

            cacheValue = cache.get(6l);

            Assert.assertEquals(controlValue, cacheValue);
            // check deep serialisation was actually involved
            Assert.assertNotSame(controlValue, cacheValue);
        }
    }

    protected void efficiencyImpl(final Ignite referenceGrid, final Ignite grid, final IgniteCache<Long, CacheRegionKey> referenceCache,
            final IgniteCache<Long, CacheRegionKey> cache, final String serialisationType, final String referenceSerialisationType,
            final double marginFraction)
    {
        LOGGER.info(
                "Running CacheRegionKey serialisation benchmark of 100k instances, comparing {} vs. {} serialisation, expecting relative improvement margin / difference fraction of {}",
                referenceSerialisationType, serialisationType, marginFraction);

        final CacheRegion[] regions = CacheRegion.values();

        final SecureRandom rnJesus = new SecureRandom();
        for (int idx = 0; idx < 100000; idx++)
        {
            final String region = regions[rnJesus.nextInt(regions.length - 2)].getCacheRegionName();
            // 1 billion possible IDs is more than sufficient for testing - rarely seen in production, more in benchmarks
            final CacheRegionKey value = new CacheRegionKey(region, Long.valueOf(rnJesus.nextInt(1000000000)));

            referenceCache.put(Long.valueOf(idx), value);
            cache.put(Long.valueOf(idx), value);
        }

        @SuppressWarnings("unchecked")
        final String regionName = cache.getConfiguration(CacheConfiguration.class).getDataRegionName();
        final DataRegionMetrics referenceMetrics = referenceGrid.dataRegionMetrics(regionName);
        final DataRegionMetrics metrics = grid.dataRegionMetrics(regionName);

        // sufficient to compare used pages - byte-exact memory usage cannot be determined due to potential partial page fill
        final long referenceTotalUsedPages = referenceMetrics.getTotalUsedPages();
        final long totalUsedPages = metrics.getTotalUsedPages();
        final long allowedMax = referenceTotalUsedPages - (long) (marginFraction * referenceTotalUsedPages);
        LOGGER.info("Benchmark resulted in {} vs {} (expected max of {}) total used pages", referenceTotalUsedPages, totalUsedPages,
                allowedMax);
        Assert.assertTrue(totalUsedPages <= allowedMax);
    }
}