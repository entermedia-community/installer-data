package model.push;

import java.io.UnsupportedEncodingException;
import java.util.HashMap;
import java.util.Map;

import org.apache.commons.httpclient.HttpClient
import org.apache.commons.httpclient.HttpException
import org.apache.commons.httpclient.HttpMethod
import org.apache.commons.httpclient.methods.PostMethod
import org.apache.commons.httpclient.methods.multipart.FilePart
import org.apache.commons.httpclient.methods.multipart.MultipartRequestEntity
import org.apache.commons.httpclient.methods.multipart.Part
import org.apache.commons.httpclient.methods.multipart.StringPart
import org.apache.commons.logging.Log
import org.apache.commons.logging.LogFactory
import org.dom4j.DocumentException
import org.dom4j.Element
import org.dom4j.io.OutputFormat;
import org.dom4j.io.SAXReader
import org.dom4j.io.XMLWriter;
import org.entermedia.upload.FileUpload
import org.entermedia.upload.FileUploadItem
import org.entermedia.upload.UploadRequest
import org.openedit.Data
import org.openedit.data.Searcher
import org.openedit.data.SearcherManager
import org.openedit.entermedia.Asset
import org.openedit.entermedia.Category
import org.openedit.entermedia.MediaArchive
import org.openedit.entermedia.push.PushManager
import org.openedit.entermedia.search.AssetSearcher
import org.openedit.entermedia.util.NaiveTrustManager
import org.openedit.repository.ContentItem
import org.openedit.util.DateStorageUtil

import com.openedit.OpenEditException
import com.openedit.OpenEditRuntimeException;
import com.openedit.WebPageRequest
import com.openedit.hittracker.HitTracker
import com.openedit.hittracker.SearchQuery
import com.openedit.page.Page
import com.openedit.page.manage.PageManager
import com.openedit.users.User
import com.openedit.users.UserManager
import com.openedit.util.PathUtilities

public class BasePushManager implements PushManager
{
	private static final Log log = LogFactory.getLog(PushManager.class);
	protected SearcherManager fieldSearcherManager;
	protected UserManager fieldUserManager;
	protected PageManager fieldPageManager;
	
	//protected HttpClient fieldClient;
	
	protected ThreadLocal perThreadCache = new ThreadLocal();
	
	//TODO: Put a 5 minute timeout on this connection. This way we will reconnect
	/* (non-Javadoc)
	 * @see org.openedit.entermedia.push.PushManager#login(java.lang.String)
	 */
	public HttpClient login(String inCatalogId)
	{
		HttpClient client = new HttpClient();
		String server = getSearcherManager().getData(inCatalogId, "catalogsettings", "push_server_url").get("value");
		String account = getSearcherManager().getData(inCatalogId, "catalogsettings", "push_server_username").get("value");
		String password = getUserManager().decryptPassword(getUserManager().getUser(account));
		PostMethod method = new PostMethod(server + "/media/services/rest/login.xml");

		//TODO: Support a session key and ssl
		method.addParameter("accountname", account);
		method.addParameter("password", password);
		//execute(inCatalogId, method);
		try
		{
			int status = client.executeMethod(method);
			if (status != 200)
			{
				throw new Exception(" ${method} Request failed: status code ${status}");
			}
		}
		catch ( Exception ex )
		{
			throw new OpenEditException(ex);
		}
		log.info("Login sucessful");
		return client;
	}


	/* (non-Javadoc)
	 * @see org.openedit.entermedia.push.PushManager#getUserManager()
	 */
	public UserManager getUserManager()
	{
		return fieldUserManager;
	}

	/* (non-Javadoc)
	 * @see org.openedit.entermedia.push.PushManager#setUserManager(com.openedit.users.UserManager)
	 */
	public void setUserManager(UserManager inUserManager)
	{
		fieldUserManager = inUserManager;
	}

	/* (non-Javadoc)
	 * @see org.openedit.entermedia.push.PushManager#getSearcherManager()
	 */
	public SearcherManager getSearcherManager()
	{
		return fieldSearcherManager;
	}

	/* (non-Javadoc)
	 * @see org.openedit.entermedia.push.PushManager#setSearcherManager(org.openedit.data.SearcherManager)
	 */
	public void setSearcherManager(SearcherManager inSearcherManager)
	{
		fieldSearcherManager = inSearcherManager;
	}

	/* (non-Javadoc)
	 * @see org.openedit.entermedia.push.PushManager#getClient(java.lang.String)
	 */
	public HttpClient getClient(String inCatalogId)
	{
		HttpClient ref = (HttpClient) perThreadCache.get();
		if (ref == null)
		{
			if( ref == null)
			{
				ref = login(inCatalogId);
				// use weak reference to prevent cyclic reference during GC
				perThreadCache.set(ref);
			}
		}
		return ref;
	}
	/* (non-Javadoc)
	 * @see org.openedit.entermedia.push.PushManager#processPushQueue(org.openedit.entermedia.MediaArchive, com.openedit.users.User)
	 */
	public void processPushQueue(MediaArchive archive, User inUser)
	{
		processPushQueue(archive,null,inUser);
	}
	/* (non-Javadoc)
	 * @see org.openedit.entermedia.push.PushManager#processPushQueue(org.openedit.entermedia.MediaArchive, java.lang.String, com.openedit.users.User)
	 */
	public void processPushQueue(MediaArchive archive, String inAssetIds, User inUser)
	{
		
		
		//field=importstatus&importstatus.value=complete&operation=matches&field=pushstatus&pushstatus.value=complete&operation=not&field=pushstatus&pushstatus.value=nogenerated&operation=not&field=pushstatus&
		//pushstatus.value=error&operation=not&field=pushstatus&pushstatus.value=deleted&operation=not
		
		//Searcher hot = archive.getSearcherManager().getSearcher(archive.getCatalogId(), "hotfolder");
		Searcher searcher = archive.getAssetSearcher();
		SearchQuery query = searcher.createSearchQuery();
		if( inAssetIds == null )
		{
			//query.addMatches("category","index");
			query.addMatches("importstatus","complete");
			query.addNot("pushstatus","complete");
			query.addNot("pushstatus","nogenerated");
			query.addNot("pushstatus","error");
			query.addNot("pushstatus","deleted");
			query.addNot("editstatus","7");
		}
		else
		{
			String assetids = inAssetIds.replace(","," ");
			query.addOrsGroup( "id", assetids );
		}
		query.addSortBy("assetmodificationdate");
		HitTracker hits = searcher.search(query);
		hits.setHitsPerPage(1000);
		if( hits.size() == 0 )
		{
			log.info("No new assets to push");
			return;
		}
		log.info("processing " + hits.size() + " assets to push");
		List savequeue = new ArrayList();
		int noasset = 0;
		for (Iterator iterator = hits.iterator(); iterator.hasNext();)
		{			
			Data hit = (Data) iterator.next();
			Asset asset = (Asset) archive.getAssetBySourcePath(hit.getSourcePath());
			if( asset != null )
			{
				savequeue.add(asset);
				if( savequeue.size() > 100 )
				{
					pushAssets(archive, savequeue);
					savequeue.clear();
				}
			}
			else
			{
				noasset++;
			}
		}
		log.info("Could not load " + noasset + " assets");
		if( savequeue.size() > 0 )
		{
			pushAssets(archive, savequeue);
			savequeue.clear();
		}
	}

	/* (non-Javadoc)
	 * @see org.openedit.entermedia.push.PushManager#processDeletedAssets(org.openedit.entermedia.MediaArchive, com.openedit.users.User)
	 */
	public void processDeletedAssets(MediaArchive archive, User inUser)
	{
		//Searcher hot = archive.getSearcherManager().getSearcher(archive.getCatalogId(), "hotfolder");
		Searcher searcher = archive.getAssetSearcher();
		SearchQuery query = searcher.createSearchQuery();
		//query.addMatches("category","index");
		query.addOrsGroup("pushstatus","complete resend retry"); //retry is legacy
		query.addMatches("editstatus","7");
		query.addSortBy("id");

		//Push them and mark them as pushstatus deleted
		HitTracker hits = searcher.search(query);
		hits.setHitsPerPage(1000);
		if( hits.size() == 0 )
		{
			log.info("No new assets to delete");
			return;
		}
		long deleted = 0;
		for (Iterator iterator = hits.iterator(); iterator.hasNext();)
		{
			Data data = (Data) iterator.next();
			Asset asset = archive.getAssetBySourcePath(data.getSourcePath());
			if( asset == null )
			{
				log.error("Reindex assets" + data.getSourcePath() );
				continue;
			}
			
			upload(asset, archive, "delete", null, Collections.EMPTY_LIST );
			asset.setProperty("pushstatus", "deleted");
			archive.saveAsset(asset, null);
			deleted++;
		}
		log.info("Removed " + deleted);
	}


	/* (non-Javadoc)
	 * @see org.openedit.entermedia.push.PushManager#uploadGenerated(org.openedit.entermedia.MediaArchive, com.openedit.users.User, org.openedit.entermedia.Asset, java.util.List)
	 */
	public void uploadGenerated(MediaArchive archive, User inUser, Asset target, List savequeue)
	{
		Searcher searcher = archive.getAssetSearcher();
		

		List<ContentItem> filestosend = new ArrayList<ContentItem>();

		String path = "/WEB-INF/data/" + archive.getCatalogId() + "/generated/" + target.getSourcePath();
		
		readFiles( archive.getPageManager(), path, path, filestosend );
		
		//			}
//			else
//			{
//				//Try again to run the tasks
//				archive.fireMediaEvent("importing/queueconversions", null, target);	//This will run right now, conflict?			
//				archive.fireMediaEvent("conversions/runconversion", null, target);	//This will run right now, conflict?			
//				tosend = findInputPage(archive, target, preset);
//				if (tosend.exists())
//				{
//					File file = new File(tosend.getContentItem().getAbsolutePath());
//					filestosend.add(file);
//				}
//				else
//				{
//					break;
//				}
//			}
//		}
		if( filestosend.size() > 0 )
		{
			try
			{
				upload(target, archive, "generated", path, filestosend);
				target.setProperty("pusheddate", DateStorageUtil.getStorageUtil().formatForStorage(new Date()));
				saveAssetStatus(searcher, savequeue, target, "complete", inUser);

			}
			catch (Exception e)
			{
				target.setProperty("pusherrordetails", e.toString());
				saveAssetStatus(searcher, savequeue, target, "error", inUser);
				log.error("Could not push",e);
			}
		}
		else
		{
			//upload(target, archive, "generated", filestosend);
			saveAssetStatus(searcher, savequeue, target, "nogenerated", inUser);
		}
	}


	private void readFiles(PageManager pageManager, String inRootPath,  String inPath, List<ContentItem> inFilestosend)
	{
		
		List paths = pageManager.getChildrenPaths(inPath);
		
		for (Iterator iterator = paths.iterator(); iterator.hasNext();)
		{
			String path = (String) iterator.next();
			ContentItem item = pageManager.getRepository().get(path);
			if( item.isFolder() )
			{
				readFiles(pageManager, inRootPath, path, inFilestosend);
			}
			else
			{
				inFilestosend.add( item );
			}
		}

	}


	protected void 	saveAssetStatus(Searcher searcher, List savequeue, Asset target, String inNewStatus, User inUser)
	{
		String oldstatus = target.get("pushstatus");
		if( oldstatus == null || !oldstatus.equals(inNewStatus))
		{
			target.setProperty("pushstatus", inNewStatus);
			savequeue.add(target);
			if( savequeue.size() == 100 )
			{
				searcher.saveAllData(savequeue, inUser);
				savequeue.clear();
			}
		}
	}

	protected Page findInputPage(MediaArchive mediaArchive, Asset asset, Data inPreset)
	{
		http://demo.entermediasoftware.com
		if (inPreset.get("type") == "original")
		{
			return mediaArchive.getOriginalDocument(asset);

		}
		String input = "/WEB-INF/data/" + mediaArchive.getCatalogId() + "/generated/" + asset.getSourcePath() + "/" + inPreset.get("outputfile");
		Page inputpage = mediaArchive.getPageManager().getPage(input);
		return inputpage;

	}
	protected Element execute(String inCatalogId, HttpMethod inMethod)
	{
		try
		{
			return send(inCatalogId, inMethod);
		}
		catch (Exception e)
		{	
			log.error(e);
			//try logging in again?
			perThreadCache.remove();
		}
		try
		{
			return send(inCatalogId, inMethod);
		}
		catch (Exception e)
		{	
			throw new RuntimeException(e);
		}
	}
	protected Element send(String inCatalogId, HttpMethod inMethod) throws IOException, HttpException, Exception, DocumentException
	{
		return send(getClient(inCatalogId),inCatalogId, inMethod);
	}
	protected Element send(HttpClient inClient, String inCatalogId, HttpMethod inMethod) throws IOException, HttpException, Exception, DocumentException
	{
		int status = inClient.executeMethod(inMethod);
		if (status != 200)
		{
			throw new Exception(" ${inMethod} Request failed: status code ${status}");
		}
		Element result = reader.read(inMethod.getResponseBodyAsStream()).getRootElement();
		return result;
	}
	
	protected Map<String, String> upload(Asset inAsset, MediaArchive inArchive, String inUploadType, String inRootPath, List<ContentItem> inFiles)
	{
		String server = inArchive.getCatalogSettingValue("push_server_url");
		//String account = inArchive.getCatalogSettingValue("push_server_username");
		String targetcatalogid = inArchive.getCatalogSettingValue("push_target_catalogid");
		//String password = getUserManager().decryptPassword(getUserManager().getUser(account));

		String url = server + "/media/services/rest/" + "handlesync.xml?catalogid=" + targetcatalogid;
		PostMethod method = new PostMethod(url);

		String prefix = inArchive.getCatalogSettingValue("push_asset_prefix");
		if( prefix == null)
		{
			prefix = "";
		}
		
		try
		{
			List<Part> parts = new ArrayList();
			int count = 0;
			for (Iterator iterator = inFiles.iterator(); iterator.hasNext();)
			{
				ContentItem file = (ContentItem) iterator.next();
				String name  =  PathUtilities.extractFileName( file.getPath() );
				FilePart part = new FilePart("file." + count, name, new File( file.getAbsolutePath() ));
				parts.add(part);
				count++;
			}
//			parts.add(new StringPart("username", account));
//			parts.add(new StringPart("password", password));
			for (Iterator iterator = inAsset.getProperties().keySet().iterator(); iterator.hasNext();)
			{
				String key = (String) iterator.next();
				if( !key.equals("libraries"))  //handled below
				{
					parts.add(new StringPart("field", key));
					parts.add(new StringPart(key+ ".value", inAsset.get(key)));
				}
			}
			parts.add(new StringPart("sourcepath", inAsset.getSourcePath()));
			
			parts.add(new StringPart("uploadtype", inUploadType)); 
			parts.add(new StringPart("id", prefix + inAsset.getId()));
			
			if( inAsset.getKeywords().size() > 0 )
			{
				StringBuffer buffer = new StringBuffer();
				for (Iterator iterator = inAsset.getKeywords().iterator(); iterator.hasNext();)
				{
					String keyword = (String) iterator.next();
					buffer.append( keyword );
					if( iterator.hasNext() )
					{
						buffer.append('|');
					}
				}
				parts.add(new StringPart("keywords", buffer.toString() ));
			}
			Collection libraries =  inAsset.getLibraries();
			if(  libraries != null && libraries.size() > 0 )
			{
				StringBuffer buffer = new StringBuffer();
				for (Iterator iterator = inAsset.getLibraries().iterator(); iterator.hasNext();)
				{
					String keyword = (String) iterator.next();
					buffer.append( keyword );
					if( iterator.hasNext() )
					{
						buffer.append('|');
					}
				}
				parts.add(new StringPart("libraries", buffer.toString() ));
			}

			Part[] arrayOfparts = parts.toArray(new Part[0]);

			method.setRequestEntity(new MultipartRequestEntity(arrayOfparts, method.getParams()));
			
			Element root = execute(inArchive.getCatalogId(), method);
			Map<String, String> result = new HashMap<String, String>();
			for (Object o : root.elements("asset"))
			{
				Element asset = (Element) o;
				result.put(asset.attributeValue("id"), asset.attributeValue("sourcepath"));
			}
			log.info("Sent " + server + "/" + inUploadType + "/" + inAsset.getSourcePath() + " with " + inFiles.size() + " generated files");
			return result;
		}
		catch (Exception e)
		{
			throw new OpenEditException(e);
		}
	} 
	/*
	protected boolean checkPublish(MediaArchive archive, Searcher pushsearcher, String assetid, User inUser)
	{
		Data hit = (Data) pushsearcher.searchByField("assetid", assetid);
		String oldstatus = null;
		Asset asset = null;
		if (hit == null)
		{
			hit = pushsearcher.createNewData();
			hit.setProperty("assetid", assetid);
			oldstatus = "none";
			asset = archive.getAsset(assetid);
			hit.setSourcePath(asset.getSourcePath());
			hit.setProperty("assetname", asset.getName());
			hit.setProperty("assetfilesize", asset.get("filesize"));
		}
		else
		{
			oldstatus = hit.get("status");
			if( "1pushcomplete".equals( oldstatus ) )
			{
				return false;
			}
			asset = archive.getAssetBySourcePath(hit.getSourcePath());
		}
		if( log.isDebugEnabled() )
		{
			log.debug("Checking 		String server = inArchive.getCatalogSettingValue("push_server_url");
		//String account = inArchive.getCatalogSettingValue("push_server_username");
		String targetcatalogid = inArchive.getCatalogSettingValue("push_target_catalogid");
		//String password = getUserManager().decryptPassword(getUserManager().getUser(account));

		String url = server + "/media/services/rest/" + "handlesync.xml?catalogid=" + targetcatalogid;
		PostMethod method = new PostMethod(url);

		String prefix = inArchive.getCatalogSettingValue("push_asset_prefix");
		if( prefix == null)
		{
			prefix = "";
		}
		
		try
		{
			List<Part> parts = new ArrayList();
			int count = 0;
			for (Iterator iterator = inFiles.iterator(); iterator.hasNext();)
			{
				File file = (File) iterator.next();
				FilePart part = new FilePart("file." + count, file.getName(),upload file);
				parts.add(part);
				count++;
			}
//			parts.add(new StringPart("username", account));
//			parts.add(new StringPart("password", password));
			for (Iterator iterator = inAsset.getProperties().keySet().iterator(); iterator.hasNext();)
			{
				String key = (String) iterator.next();
				parts.add(new StringPart("field", key));
				parts.add(new StringPart(key+ ".value", inAsset.get(key)));
			}
			parts.add(new StringPart("sourcepath", inAsset.getSourcePath()));
			
			if(inAsset.getName() != null )
			{upload(target, archive, filestosend);
				parts.add(new StringPart("original", inAsset.getName())); //What is this?
			}
			parts.add(new StringPart("id", prefix + inAsset.getId()));
			
//			StringBuffer buffer = new StringBuffer();
//			for (Iterator iterator = inAsset.getCategories().iterator(); iterator.hasNext();)
//			{
//				Category cat = (Category) iterator.next();
//				buffer.append( cat );
//				if( iterator.hasNext() )
//				{
//					buffer.append(' ');
//				}
//			}
//			parts.add(new StringPart("catgories", buffer.toString() ));
			
			Part[] arrayOfparts = parts.toArray(new Part[] {});

			method.setRequestEntity(new MultipartRequestEntity(arrayOfparts, method.getParams()));
			
			Element root = execute(inArchive.getCatalogId(), method);
			Map<String, String> result = new HashMap<String, String>();
			for (Object o : root.elements("asset"))
			{
				Element asset = (Element) o;
				result.put(asset.attributeValue("id"), asset.attributeValue("sourcepath"));
			}
			log.info("Sent " + server + "/" + inAsset.getSourcePath());
			return result;
		}
		catch (Exception e)
		{
			throw new OpenEditException(e);
		}
asset: " + asset);
		}
		
		if(asset == null)
		{
			return false;
		}
		String rendertype = archive.getMediaRenderType(asset.getFileFormat());
		if( rendertype == null )
		{
			rendertype = "document";
		}
		boolean readyforpush = true;
		Collection presets = archive.getCatalogSettingValues("push_convertpresets");
		for (Iterator iterator2 = presets.iterator(); iterator2.hasNext();)
		{
			String presetid = (String) iterator2.next();
			Data preset = archive.getSearcherManager().getData(archive.getCatalogId(), "convertpreset", presetid);
			if( rendertype.equals(preset.get("inputtype") ) )
			{
				Page tosend = findInputPage(archive, asset, preset);
				if (!tosend.exists())
				{
					if( log.isDebugEnabled() )
					{
						log.debug("Convert not ready for push " + tosend.getPath());
					}
					readyforpush = false;
					break;
				}
			}
		}
		String newstatus = null;
		if( readyforpush )
		{
			newstatus = "3readyforpush";
			hit.setProperty("percentage","0");
		}
		else
		{
			newstatus = "2converting";			
		}
		if( !newstatus.equals(oldstatus) )
		{
			hit.setProperty("status", newstatus);
			pushsearcher.saveData(hit, inUser);
		}
		return readyforpush;
	}
	*/
	/* (non-Javadoc)
	 * @see org.openedit.entermedia.push.PushManager#resetPushStatus(org.openedit.entermedia.MediaArchive, java.lang.String, java.lang.String)
	 */
	public void resetPushStatus(MediaArchive inArchive, String oldStatus,String inNewStatus)
	{
		AssetSearcher assetSearcher = inArchive.getAssetSearcher();
		List savequeue = new ArrayList();
		HitTracker hits = assetSearcher.fieldSearch("pushstatus", oldStatus);
		hits.setHitsPerPage(1000);

		int size = 0;
		while(true)
		{
			size = hits.size();
			for (Iterator iterator = hits.getPageOfHits().iterator(); iterator.hasNext();)
			{
				Data data = (Data) iterator.next();
				Asset asset = inArchive.getAssetBySourcePath(data.getSourcePath());
				if( asset == null )
				{
					log.error("Missing asset" + data.getSourcePath());
					continue;
				}
				asset.setProperty("pushstatus", inNewStatus);
				savequeue.add(asset);
				if( savequeue.size() == 1000 )
				{
					assetSearcher.saveAllData(savequeue, null);
					savequeue.clear();
				}
			}
			assetSearcher.saveAllData(savequeue, null);
			savequeue.clear();
			hits = assetSearcher.fieldSearch("pushstatus", oldStatus);
			hits.setHitsPerPage(1000);
			log.info(hits.size() + " remaining status updates " + oldStatus );
			if( hits.size() == 0 || size > hits.size() )
			{
				break;
			}
		} 
		
		
	}
	
	/* (non-Javadoc)
	 * @see org.openedit.entermedia.push.PushManager#getCompletedAssets(org.openedit.entermedia.MediaArchive)
	 */
	public Collection getCompletedAssets(MediaArchive inArchive)
	{
		HitTracker hits = inArchive.getAssetSearcher().fieldSearch("pushstatus", "complete");
		return hits;
	}

	/* (non-Javadoc)
	 * @see org.openedit.entermedia.push.PushManager#getPendingAssets(org.openedit.entermedia.MediaArchive)
	 */
	public Collection getPendingAssets(MediaArchive inArchive)
	{
		SearchQuery query = inArchive.getAssetSearcher().createSearchQuery();
		query.addMatches("importstatus","complete");
		query.addNot("pushstatus","complete");
		query.addNot("pushstatus","nogenerated");
		query.addNot("pushstatus","error");
		query.addNot("pushstatus","deleted");
		query.addNot("editstatus","7");


		HitTracker hits = inArchive.getAssetSearcher().search(query);
		return hits;
	}


	/* (non-Javadoc)
	 * @see org.openedit.entermedia.push.PushManager#getNoGenerated(org.openedit.entermedia.MediaArchive)
	 */
	public Collection getNoGenerated(MediaArchive inArchive)
	{
		HitTracker hits = inArchive.getAssetSearcher().fieldSearch("pushstatus", "nogenerated");
		return hits;
	}



	/* (non-Javadoc)
	 * @see org.openedit.entermedia.push.PushManager#getErrorAssets(org.openedit.entermedia.MediaArchive)
	 */
	public Collection getErrorAssets(MediaArchive inArchive)
	{
		HitTracker hits = inArchive.getAssetSearcher().fieldSearch("pushstatus", "error");
		return hits;
	}

	/* (non-Javadoc)
	 * @see org.openedit.entermedia.push.PushManager#getImportCompleteAssets(org.openedit.entermedia.MediaArchive)
	 */
	public Collection getImportCompleteAssets(MediaArchive inArchive)
	{
		SearchQuery query = inArchive.getAssetSearcher().createSearchQuery();
		//query.addMatches("category","index");
		query.addMatches("importstatus","complete");
		query.addNot("editstatus","7");

		//Push them and mark them as pushstatus deleted
		HitTracker hits = inArchive.getAssetSearcher().search(query);
		return hits;
	}

	/* (non-Javadoc)
	 * @see org.openedit.entermedia.push.PushManager#getImportPendingAssets(org.openedit.entermedia.MediaArchive)
	 */
	public Collection getImportPendingAssets(MediaArchive inArchive)
	{
		SearchQuery query = inArchive.getAssetSearcher().createSearchQuery();
		query.addMatches("importstatus","imported");
		query.addNot("editstatus","7");

		//Push them and mark them as pushstatus deleted
		HitTracker hits = inArchive.getAssetSearcher().search(query);
		return hits;
	}

	/* (non-Javadoc)
	 * @see org.openedit.entermedia.push.PushManager#getImportErrorAssets(org.openedit.entermedia.MediaArchive)
	 */
	public Collection getImportErrorAssets(MediaArchive inArchive)
	{
		HitTracker hits = inArchive.getAssetSearcher().fieldSearch("importstatus", "error");
		return hits;
	}


	/* (non-Javadoc)
	 * @see org.openedit.entermedia.push.PushManager#pushAssets(org.openedit.entermedia.MediaArchive, java.util.List)
	 */
	public void pushAssets(MediaArchive inArchive, List<Asset> inAssetsSaved)
	{
		String enabled = inArchive.getCatalogSettingValue("push_masterswitch");
		if( "false".equals(enabled) )
		{
			log.info("Push is paused");
			return;
		}

		List tosave = new ArrayList();
		//convert then save
		for (Iterator iterator = inAssetsSaved.iterator(); iterator.hasNext();)
		{
			Asset asset = (Asset) iterator.next();
			uploadGenerated(inArchive, null, asset, tosave);
		}
		inArchive.getAssetSearcher().saveAllData(tosave, null);
		
	}


	/* (non-Javadoc)
	 * @see org.openedit.entermedia.push.PushManager#pollRemotePublish(org.openedit.entermedia.MediaArchive)
	 */
	public void pollRemotePublish(MediaArchive inArchive)
	{
		
		String enabled = inArchive.getCatalogSettingValue("push_masterswitch");
		if( enabled == null || "false".equals(enabled) )
		{
			//log.info("Push is paused");
			return;
		}

		
		fieldClient = null;
		
		String server = inArchive.getCatalogSettingValue("push_server_url");
		String targetcatalogid = inArchive.getCatalogSettingValue("push_target_catalogid");

		String url = server + "/media/services/rest/searchpendingpublish.xml?catalogid=" + targetcatalogid;
		//url = url + "&field=remotempublishstatus&remotempublishstatus.value=new&operation=exact";
		PostMethod method = new PostMethod(url);

		//loop over all the destinations we are monitoring
////		Searcher dests = getSearcherManager().getSearcher(inArchive.getCatalogId(),"publishdestination");
////		Collection hits = dests.fieldSearch("remotempublish","true");
////		if( hits.size() == 0 )
////		{
////			log.info("No remote publish destinations defined. Disable Pull Remote Event");
////			return;
////		}
//		StringBuffer ors = new StringBuffer();
//		for (Iterator iterator = hits.iterator(); iterator.hasNext();)
//		{
//			Data dest = (Data) iterator.next();
//			ors.append(dest.getId());
//			if( iterator.hasNext() )
//			{
//				ors.append(" ");
//			}
//		}
		method.addParameter("field", "remotepublish");
		method.addParameter("remotepublish.value", "true");
		method.addParameter("operation", "matches");

		method.addParameter("field", "status");
		method.addParameter("status.value", "complete");
		method.addParameter("operation", "not");

		method.addParameter("field", "status");
		method.addParameter("status.value", "error");
		method.addParameter("operation", "not");

		try
		{
			Element root = execute(inArchive.getCatalogId(), method);
			if( root.elements().size() > 0 )
			{
				log.info("polled " + root.elements().size() + " children" );
			}
			for (Object row : root.elements("hit"))
			{
				Element hit = (Element)row;
				try
				{
					runRemotePublish(inArchive, server, targetcatalogid, hit);
				}
				catch (Exception e)
				{
					log.error("Could not save publish " , e);
				}
			}
		}
		catch (Exception e)
		{
			throw new OpenEditException(e);
		}

	}


	protected void runRemotePublish(MediaArchive inArchive, String server, String targetcatalogid, Element hit) throws Exception
	{
		String sourcepath = hit.attributeValue("assetsourcepath");
		Asset asset = inArchive.getAssetBySourcePath(sourcepath);
		String publishtaskid = hit.attributeValue("id");
		String saveurl = server + "/media/services/rest/savedata.xml?save=true&catalogid=" + targetcatalogid + "&searchtype=publishqueue&id=" + publishtaskid;
		if( asset == null )
		{
			log.info("Asset not found: " + sourcepath);
			saveurl = saveurl + "&field=status&status.value=error";
			saveurl = saveurl + "&field=errordetails&errordetails.value=original_asset_not_found";
			PostMethod savemethod = new PostMethod(saveurl);
			Element saveroot = execute(inArchive.getCatalogId(), savemethod);
		}
		else
		{

			String presetid = hit.attributeValue("presetid");
			String destinationid = hit.attributeValue("publishdestination");
			
			Data preset = getSearcherManager().getData(inArchive.getCatalogId(), "convertpreset", presetid);

			String exportpath = hit.attributeValue("exportpath");

			Data publishedtask = convertAndPublish(inArchive, asset, publishtaskid, preset, destinationid, exportpath);

			Page inputpage = null;
			String type = null;
			if( !"original".equals(preset.get("type")))
			{
				String input= "/WEB-INF/data/" + inArchive.getCatalogId() +  "/generated/" + asset.getSourcePath() + "/" + preset.get("outputfile");
				inputpage= inArchive.getPageManager().getPage(input);
				type = "generated";
			}
			else
			{
				inputpage = inArchive.getOriginalDocument(asset);
				type = "originals";
			}
			if( inputpage.length() == 0 )
			{
				saveurl = saveurl + "&field=status&status.value=error";
				//saveurl = saveurl + "&field=remotempublishstatus&remotempublishstatus.value=error";
				saveurl = saveurl + "&field=errordetails&errordetails.value=output_not_found";
				PostMethod savemethod = new PostMethod(saveurl);
				Element saveroot = execute(inArchive.getCatalogId(), savemethod);
				return;
			}

			String status = publishedtask.get("status");

			//saveurl = saveurl + "&field=remotempublishstatus&remotempublishstatus.value=" +  status;
			saveurl = saveurl + "&field=status&status.value=" + status;
			if( status.equals("error") )
			{
				String errordetails = publishedtask.get("errordetails");
				if( errordetails != null )
				{
					saveurl = saveurl + "&field=errordetails&errordetails.value=" + URLEncoder.encode(errordetails,"UTF-8");
				}

			} 
			else if( destinationid.equals("0") )
			{
				//If this is a browser download then we need to upload the file
				List<ContentItem> filestosend = new ArrayList<ContentItem>(1);

				filestosend.add(inputpage.getContentItem());

				String 	rootpath = "/WEB-INF/data/" + inArchive.getCatalogId() +  "/originals/" + asset.getSourcePath();
				
				upload(asset, inArchive, type, rootpath, filestosend);
			}

			
			PostMethod savemethod = new PostMethod(saveurl);
			Element saveroot = execute(inArchive.getCatalogId(), savemethod);					
		}
	}
	
	protected Data convertAndPublish(MediaArchive inArchive, Asset inAsset, String publishqueueid, Data preset, String destinationid, String exportpath) throws Exception
	{
		boolean needstobecreated = true;
		String outputfile = preset.get("outputfile");

		//Make sure preset does not already exists?
		if( needstobecreated && "original".equals( preset.get("type") ) )
		{
			needstobecreated = false;
		}			
		if( needstobecreated && inArchive.doesAttachmentExist(outputfile, inAsset) )
		{
			needstobecreated = false;
		}
		String assetid = inAsset.getId();
		if (needstobecreated)
		{
			Searcher taskSearcher = getSearcherManager().getSearcher(inArchive.getCatalogId(), "conversiontask");
			//TODO: Make sure it is not already in here
			SearchQuery q = taskSearcher.createSearchQuery().append("assetid", assetid).append("presetid", preset.getId());
			HitTracker hits = taskSearcher.search(q);
			if( hits.size() == 0 )
			{
				Data newTask = taskSearcher.createNewData();
				newTask.setSourcePath(inAsset.getSourcePath());
				newTask.setProperty("status", "new");
				newTask.setProperty("assetid", assetid);
				newTask.setProperty("presetid", preset.getId());
				taskSearcher.saveData(newTask, null);
			}
			//TODO: Make sure it finished?
			inArchive.fireMediaEvent("conversions/runconversion", null, inAsset);
		}
		
		//Add a publish task to the publish queue
		Searcher publishQueueSearcher = getSearcherManager().getSearcher(inArchive.getCatalogId(), "publishqueue");
		Data publishqeuerow =  (Data)publishQueueSearcher.searchById("remote" + publishqueueid);
		if( publishqeuerow == null )
		{
			publishqeuerow = publishQueueSearcher.createNewData();
			publishqeuerow.setId("remote" + publishqueueid);
			publishqeuerow.setProperty("status", "new");
			publishqeuerow.setProperty("assetid", assetid);
			publishqeuerow.setProperty("publishdestination", destinationid);
			publishqeuerow.setProperty("presetid", preset.getId() );
			//Why is this not being passed back to us?
			if( exportpath == null )
			{
				exportpath = inArchive.asExportFileName(inAsset, preset);
			}
			publishqeuerow.setProperty("exportname", exportpath);
			publishqeuerow.setSourcePath(inAsset.getSourcePath());
			publishqeuerow.setProperty("date", DateStorageUtil.getStorageUtil().formatForStorage(new Date()));
			publishQueueSearcher.saveData(publishqeuerow, null);
		}
		inArchive.fireMediaEvent("publishing/publishasset", null, inAsset);
		
		publishqeuerow =  (Data)publishQueueSearcher.searchById("remote" + publishqueueid);
		return publishqeuerow;
	}


	/* (non-Javadoc)
	 * @see org.openedit.entermedia.push.PushManager#toggle(java.lang.String)
	 */
	public void toggle(String inCatalogId)
	{
		fieldClient = null;
	}
	
	@Override
	public void acceptPush(WebPageRequest inReq, MediaArchive archive)
	{
		FileUpload command = new FileUpload();
		command.setPageManager(archive.getPageManager());
		UploadRequest properties = command.parseArguments(inReq);

		String sourcepath = inReq.getRequestParameter("sourcepath");
		
		Asset target = archive.getAssetBySourcePath(sourcepath);
		if (target == null)
		{
			String id = inReq.getRequestParameter("id");
			target = archive.createAsset(id, sourcepath);
		}
		
//		String categories = inReq.getRequestParameter("categories");
//		String[] vals = categories.split(";");
//		archive.c
//		target.setCategories(cats);
		String categorypath = PathUtilities.extractDirectoryPath(sourcepath);
		Category category = archive.getCategoryArchive().createCategoryTree(categorypath);
		target.addCategory(category);
		
		String[] fields = inReq.getRequestParameters("field");
		
		//Make sure we ADD libraries not replace them
		Collection existing = target.getLibraries();
		archive.getAssetSearcher().updateData(inReq, fields, target);

		String libraries = inReq.getRequestParameter("libraries");
		if( libraries != null )
		{
			String[] keys =  libraries.split("\\|");
			for (int i = 0; i < keys.length; i++)
			{
				existing.add(keys[i]);
			}
		}
		target.setValues("libraries", existing);

		String keywords = inReq.getRequestParameter("keywords");
		if( keywords != null )
		{
			String[] keys =  keywords.split("\\|");
			for (int i = 0; i < keys.length; i++)
			{
				target.addKeyword(keys[i]);
			}
		}

		
		archive.saveAsset(target, inReq.getUser());
		List<FileUploadItem> uploadFiles = properties.getUploadItems();

		String type = inReq.findValue("uploadtype");
		if( type == null )
		{
			type = "generated";
		}
		String	saveroot = "/WEB-INF/data/" + archive.getCatalogId() + "/" + type + "/" + sourcepath;
			
		//String originalsroot = "/WEB-INF/data/" + archive.getCatalogId() + "/originals/" + sourcepath + "/";

		if (uploadFiles != null)
		{
			Iterator<FileUploadItem> iter = uploadFiles.iterator();
			while (iter.hasNext())
			{
				FileUploadItem fileItem = iter.next();

				String filename = fileItem.getName();
				if (type.equals("originals"))
				{
					properties.saveFileAs(fileItem, saveroot, inReq.getUser());
				}
				else
				{
					properties.saveFileAs(fileItem, saveroot + "/" + filename, inReq.getUser());
				}
			}
		}

	}


}
