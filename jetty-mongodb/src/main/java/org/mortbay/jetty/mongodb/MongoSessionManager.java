package org.mortbay.jetty.mongodb;


import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.UnknownHostException;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import org.eclipse.jetty.util.log.Log;

import com.mongodb.BasicDBObject;
import com.mongodb.BasicDBObjectBuilder;
import com.mongodb.DBCollection;
import com.mongodb.DBObject;
import com.mongodb.Mongo;
import com.mongodb.MongoException;

public class MongoSessionManager extends NoSqlSessionManager
{
    final static DBObject __version_1 = new BasicDBObject("version",1); 
    final DBCollection _sessions;
    
    public MongoSessionManager() throws UnknownHostException, MongoException
    {
        this(new Mongo().getDB("HttpSessions").getCollection("sessions"));
    }
    
    public MongoSessionManager(DBCollection sessions)
    {
        _sessions=sessions;

        _sessions.ensureIndex(
                BasicDBObjectBuilder.start()
                .add("id",1).get(),
                BasicDBObjectBuilder.start()
                .add("unique",true)
                .add("sparse",false)
                .get());
        _sessions.ensureIndex(
                BasicDBObjectBuilder.start()
                .add("id",1)
                .add("version",1).get(),
                BasicDBObjectBuilder.start()
                .add("unique",true)
                .add("sparse",false)
                .get());
    }

    @Override
    protected synchronized Object save(NoSqlSession session, String canonicalContext, Object version, boolean activateAfterSave)
    {
        try
        {
            System.err.println("Save "+session);
            session.willPassivate();
            ByteArrayOutputStream bout = new ByteArrayOutputStream();
            ObjectOutputStream out = new ObjectOutputStream(bout);
            
            // Form query for upsert
            BasicDBObject key = new BasicDBObject("id",session.getClusterId());
            key.put("valid",true);
            
            // Form updates
            BasicDBObject update=new BasicDBObject();
            boolean upsert=false;
            BasicDBObject sets = new BasicDBObject();
            BasicDBObject unsets = new BasicDBObject();
            
            // handle new or existing
            if (version==null)
            {
                // New session
                upsert=true;
                version=new Long(1);
                sets.put("created",session.getCreationTime());
                sets.put("version",version);
            }
            else
            {
                version=new Long(((Long)version).intValue()+1);
                update.put("$inc",__version_1);
            }   
            
            // handle valid or invalid
            if (session.isValid())
            {
                sets.put("accessed",session.getAccessed());
                Set<String> names = session.takeDirty();
                if (isSaveAllAttributes()||upsert)
                    names.addAll(session.getNames()); // note dirty may include removed names
                
                for (String name:names)
                {
                    Object value = session.getAttribute(name);
                    if (value==null)
                        unsets.put(canonicalContext + "." +encodeName(name),1);
                    else
                        sets.put(canonicalContext + "." +encodeName(name),encodeName(out,bout,value));
                }
            }
            else
            {
                sets.put("valid",false);
                unsets.put(canonicalContext,1);
            }
            
            // Do the upsert
            if (!sets.isEmpty())
                update.put("$set",sets);
            if (!unsets.isEmpty())
                update.put("$unset",unsets);
            
            _sessions.update(key,update,upsert,false);
            System.err.println("db.sessions.update("+key+","+update+",true)");

            if (activateAfterSave)
                session.didActivate();
            
            return version;
        }
        catch(Exception e)
        {
            Log.warn(e);
        }
        return null;
    }

    
    @Override
    protected Object refresh(NoSqlSession session, String canonicalContext, Object version)
    {
        System.err.println("Refresh "+session);

        // check if our in memory version is the same as what is on the disk
        if (version!=null)
        {
            DBObject o =_sessions.findOne(new BasicDBObject("id",session.getClusterId()),__version_1);

            if (o!=null) 
            {
                Object saved = o.get("version");
                if (saved!=null && saved.equals(version))
                {
                    System.err.println("Refresh not needed");
                    return version;
                }
                version=saved;
            }
        }
        
        // If we are here, we have to load the object
        DBObject o =_sessions.findOne(new BasicDBObject("id",session.getClusterId()),__version_1);
        
        // If it doesn't exist, invalidate
        if (o==null)
        {
            session.invalidate();
            return null;
        }
        
        // If it has been flaged invalid, invalidate
        Boolean valid = (Boolean)o.get("valid");
        if (valid==null || !valid)
        {
            session.invalidate();
            return null;
        }

        // We need to update the attributes.  We will model this as a passivate, 
        // followed by bindings and then activation.
        session.willPassivate();
        try
        {
            session.clearAttributes();
            DBObject attrs=(DBObject)o.get(canonicalContext);
            if (attrs!=null)
            {
                for (String name : attrs.keySet())
                {
                    String attr=decodeName(name);
                    Object value=decodeValue(o.get(name));
                    session.doPutOrRemove(attr,value);
                    session.bindValue(attr,value);
                }
            }

            session.didActivate();
            return version; 
        }
        catch(Exception e)
        {
            Log.warn(e);
        }
        
        return null;
    }

    @Override
    protected synchronized NoSqlSession loadSession(String clusterId, final String canonicalContext) 
    {
        System.err.println("loadSession "+clusterId + "/" + canonicalContext);

        DBObject o =_sessions.findOne(new BasicDBObject("id",clusterId));
        System.err.println("loaded "+o);
        if (o==null)
            return null;
        Boolean valid = (Boolean)o.get("valid");
        if (valid==null || !valid)
            return null;
        
        Object version = o.get("version");
        try
        {
            NoSqlSession session = new NoSqlSession(this,(Long)o.get("created"),(Long)o.get("accessed"),clusterId,canonicalContext,version);

            // get the attributes for the context
            DBObject attrs=(DBObject)o.get(canonicalContext);
            System.err.println("attrs: "+attrs);
            if (attrs!=null)
            {
                for (String name : attrs.keySet())
                {
                    String attr=decodeName(name);
                    Object value=decodeValue(attrs.get(name));

                    System.err.println("put "+attr+":"+value);
                    session.doPutOrRemove(attr,value);
                    session.bindValue(attr,value);
                }
            }
            session.didActivate();
            return session; 
        }
        catch(Exception e)
        {
            Log.warn(e);
        }
        return null;
    }
    
    @Override
	protected boolean remove(NoSqlSession session, String canonicalContext) 
    {             
        // If we are here, we have to load the object
        DBObject o =_sessions.findOne(new BasicDBObject("id",session.getClusterId()),__version_1);
  
        BasicDBObject key = new BasicDBObject("id",session.getClusterId());
        
        if ( o != null )
        {
			BasicDBObject remove = new BasicDBObject();
			BasicDBObject unsets = new BasicDBObject();
			unsets.put(canonicalContext, 1);
			remove.put("$unsets", unsets);
			_sessions.update(key, remove);
			
			return true;
		}
        else
        {
        	return false;
        }
	}

	protected String encodeName(String name)
    {
        return name.replace("%","%25").replace(".","%2E");
    }

    protected String decodeName(String name)
    {
        return name.replace("%2E",".").replace("%25","%");
    }
    
    protected Object encodeName(ObjectOutputStream out, ByteArrayOutputStream bout, Object value) throws IOException
    {
        if (value instanceof Number || 
            value instanceof String ||
            value instanceof Boolean ||
            value instanceof Date)
        {
            return value;
        }
        else if (value.getClass().equals(HashMap.class))
        {
            BasicDBObject o = new BasicDBObject();
            for (Map.Entry<?,?> entry : ((Map<?,?>)value).entrySet())
            {
                if (!(entry.getKey() instanceof String))
                {
                    o=null;
                    break;
                }
                o.append(encodeName(entry.getKey().toString()),encodeName(out,bout,value));
            }
            
            if (o!=null)
                return o;
        }
        
        bout.reset();
        out.reset();
        out.writeUnshared(value);
        out.flush();
        return bout.toByteArray();
    }
    
    protected Object decodeValue(Object value) throws IOException, ClassNotFoundException
    {
        if (value==null ||
            value instanceof Number || 
            value instanceof String ||
            value instanceof Boolean ||
            value instanceof Date)
        {
            return value;
        }
        else if (value instanceof byte[])
        {
            ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream((byte[])value));
            return in.readObject();
        }
        else if (value instanceof DBObject)
        {
            Map<String,Object> map = new HashMap<String,Object>();
            for (String name : ((DBObject)value).keySet())
            {
                String attr = decodeName(name);
                map.put(attr,decodeValue(((DBObject)value).get(name)));
            }
            return map;
        }
        else
        {
            throw new IllegalStateException(value.getClass().toString());
        }
    }
}
