package com.example.floatingassistant

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters

/**
 * CloudPathDatabaseInstrumentedTest
 *
 * This test class is used to verify the Firestore-backed CloudPathDatabase.
 * It contains four main tests:
 * 1. [test01_add_whatsapp_profile_path]: Verifies that an entry can be added correctly to Firestore.
 * 2. [test02_fetch_whatsapp_profile_path]: Verifies that an existing entry can be fetched correctly.
 * 3. [test03_fetch_wrong_query]: Verifies that lookup returns empty for non-existent intents.
 * 4. [test04_fetch_non_existent_device_signature]: Verifies that lookup returns empty for a signature not in Firestore.
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
@RunWith(AndroidJUnit4::class)
class CloudPathDatabaseInstrumentedTest {

    private val testDevice = CloudPathDatabase.currentDeviceSignatureInfo()
    private val whatsappIntent = "change_whatsapp_profile_picture"
    private val whatsappPath = "WhatsApp -> 3 dots -> Settings -> Profile -> Change Profile"

    /**
     * Test 1: Add data to Firestore.
     * This test verifies that the 'addEntry' function correctly pushes data to the cloud.
     * It does not clean up the data so that Test 2 can verify the fetch.
     */
    @Test
    fun test01_add_whatsapp_profile_path() {
        runBlocking {
            CloudPathDatabase.ensureSignedIn()

            Log.i("CloudPathTest", "Step 1: Adding entry to Firestore for intent: $whatsappIntent")
            
            // Add the entry
            CloudPathDatabase.addEntry(whatsappIntent, whatsappPath, testDevice)
            
            // Verify immediately after adding
            val result = CloudPathDatabase.lookup(whatsappIntent, testDevice)
            
            Log.i("CloudPathTest", "Step 1 Result: '$result'")
            assertEquals("The path should be added and retrievable immediately", whatsappPath, result)
        }
    }

    /**
     * Test 2: Fetch data from Firestore.
     * This test verifies that the 'lookup' function correctly retrieves existing data from the cloud.
     * Run this AFTER Test 1 to ensure the data is present.
     */
    @Test
    fun test02_fetch_whatsapp_profile_path() {
        runBlocking {
            CloudPathDatabase.ensureSignedIn()

            Log.i("CloudPathTest", "Step 2: Fetching existing entry from Firestore for intent: $whatsappIntent")
            
            // Perform lookup only
            val result = CloudPathDatabase.lookup(whatsappIntent, testDevice)
            
            Log.i("CloudPathTest", "Step 2 Result: '$result'")
            
            assertTrue("The entry should exist in Firestore. Ensure test_add_whatsapp_profile_path was run first.", result.isNotEmpty())
            assertEquals("The fetched path should match the expected WhatsApp path", whatsappPath, result)
        }
    }

    /**
     * Test 3: Fetch non-existent data.
     * This test verifies that the 'lookup' function correctly returns an empty string
     * when the intent is not found in Firestore.
     */
    @Test
    fun test03_fetch_wrong_query() {
        runBlocking {
            CloudPathDatabase.ensureSignedIn()

            val wrongIntent = "some_random_non_existent_intent_${System.currentTimeMillis()}"
            Log.i("CloudPathTest", "Step 3: Fetching non-existent entry for intent: $wrongIntent")
            
            val result = CloudPathDatabase.lookup(wrongIntent, testDevice)
            
            Log.i("CloudPathTest", "Step 3 Result: '$result'")
            
            assertTrue("The result should be empty for a wrong/unknown query", result.isEmpty())
        }
    }

    /**
     * Test 4: Fetch for a device signature that does not exist in Firestore.
     * This test verifies that the 'lookup' function correctly returns an empty string
     * when no document exists for the provided device signature.
     */
    @Test
    fun test04_fetch_non_existent_device_signature() {
        runBlocking {
            CloudPathDatabase.ensureSignedIn()

            // Create a fake device signature that is guaranteed to not exist
            val fakeDevice = CloudPathDatabase.DeviceSignatureInfo(
                manufacturer = "non_existent_manufacturer",
                brand = "non_existent_brand",
                androidVersion = "99"
            )

            Log.i("CloudPathTest", "Step 4: Fetching for non-existent device signature: ${CloudPathDatabase.buildSignature(fakeDevice)}")
            
            val result = CloudPathDatabase.lookup(whatsappIntent, fakeDevice)
            
            Log.i("CloudPathTest", "Step 4 Result: '$result'")
            
            assertTrue("The result should be empty for a non-existent device signature", result.isEmpty())
        }
    }
}
