package mcp.mcinjector;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Properties;
import java.util.Vector;
import java.util.logging.FileHandler;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import mcp.StringUtil;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LocalVariableNode;
import org.objectweb.asm.tree.MethodNode;

public class MCInjectorImpl
{
    private final static Logger log = Logger.getLogger("MCInjector");
    public final Properties mappings = new Properties();
    public final Properties outMappings = new Properties()
    {
        private static final long serialVersionUID = 4112578634029874840L;

        @Override
        @SuppressWarnings({ "unchecked", "rawtypes" })
        public synchronized Enumeration keys()
        {
            Enumeration keysEnum = super.keys();
            Vector keyList = new Vector();
            while (keysEnum.hasMoreElements())
            {
                keyList.add(keysEnum.nextElement());
            }
            Collections.sort(keyList);
            return keyList.elements();
        }
    };
    private int initIndex = 0;

    public static void process(String inFile, String outFile, String mapFile, String logFile, String outMapFile, int index)
        throws IOException
    {
        MCInjectorImpl.log.setUseParentHandlers(false);
        MCInjectorImpl.log.setLevel(Level.ALL);

        if (logFile != null)
        {
            FileHandler filehandler = new FileHandler(logFile);
            filehandler.setFormatter(new LogFormatter());
            MCInjectorImpl.log.addHandler(filehandler);
        }

        MCInjectorImpl mci = new MCInjectorImpl(index);
        mci.loadMap(mapFile);
        mci.processJar(inFile, outFile);
        if (outMapFile != null)
        {
            mci.saveMap(outMapFile);
        }

        System.out.println("Processed " + inFile);
    }

    private MCInjectorImpl(int index)
    {
        this.initIndex = index;
    }

    public void loadMap(String mapFile) throws IOException
    {
        Reader mapReader = null;
        try
        {
            mapReader = new FileReader(mapFile);
            this.mappings.load(mapReader);
        }
        catch (IOException e)
        {
            throw new IOException("Could not open map file: " + e.getMessage());
        }
        finally
        {
            if (mapReader != null)
            {
                try
                {
                    mapReader.close();
                }
                catch (IOException e)
                {
                    // ignore;
                }
            }
        }
    }

    public void saveMap(String mapFile) throws IOException
    {
        Writer mapWriter = null;
        try
        {
            mapWriter = new FileWriter(mapFile);
            if (this.initIndex > 0)
            {
                this.outMappings.store(mapWriter, "max index=" + this.initIndex);
            }
            else
            {
                this.outMappings.store(mapWriter, null);
            }
        }
        catch (IOException e)
        {
            throw new IOException("Could not write map file: " + e.getMessage());
        }
        finally
        {
            if (mapWriter != null)
            {
                try
                {
                    mapWriter.close();
                }
                catch (IOException e)
                {
                    // ignore;
                }
            }
        }
    }

    public void processJar(String inFile, String outFile) throws IOException
    {
        ZipInputStream inJar = null;
        ZipOutputStream outJar = null;

        try
        {
            try
            {
                inJar = new ZipInputStream(new BufferedInputStream(new FileInputStream(inFile)));
            }
            catch (FileNotFoundException e)
            {
                throw new FileNotFoundException("Could not open input file: " + e.getMessage());
            }

            try
            {
                outJar = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(outFile)));
            }
            catch (FileNotFoundException e)
            {
                throw new FileNotFoundException("Could not open output file: " + e.getMessage());
            }

            while (true)
            {
                ZipEntry entry = inJar.getNextEntry();

                if (entry == null)
                {
                    break;
                }

                if (entry.isDirectory())
                {
                    outJar.putNextEntry(entry);
                    continue;
                }

                byte[] data = new byte[4096];
                ByteArrayOutputStream entryBuffer = new ByteArrayOutputStream();

                int len;
                do
                {
                    len = inJar.read(data);
                    if (len > 0)
                    {
                        entryBuffer.write(data, 0, len);
                    }
                } while (len != -1);

                byte[] entryData = entryBuffer.toByteArray();

                String entryName = entry.getName();

                if (entryName.endsWith(".class") && !entryName.startsWith("."))
                {
                    MCInjectorImpl.log.log(Level.INFO, "Processing " + entryName);

                    entryData = this.processClass(entryData);

                    MCInjectorImpl.log.log(Level.INFO, "Processed " + entryBuffer.size() + " -> " + entryData.length);
                }
                else
                {
                    MCInjectorImpl.log.log(Level.INFO, "Copying " + entryName);
                }

                ZipEntry newEntry = new ZipEntry(entryName);
                outJar.putNextEntry(newEntry);
                outJar.write(entryData);
            }
        }
        finally
        {
            if (outJar != null)
            {
                try
                {
                    outJar.close();
                }
                catch (IOException e)
                {
                    // ignore
                }
            }

            if (inJar != null)
            {
                try
                {
                    inJar.close();
                }
                catch (IOException e)
                {
                    // ignore
                }
            }
        }
    }

    public byte[] processClass(byte[] input)
    {
        ClassReader reader = new ClassReader(input);

        ClassNode classNode = new ClassNode();

        reader.accept(classNode, 0);

        MCInjectorImpl.log.log(Level.FINE, "Class: " + classNode.name + " Extends: " + classNode.superName);

        for (MethodNode methodNode : classNode.methods)
        {
            MCInjectorImpl.log.log(Level.FINER, "Name: " + methodNode.name + " Desc: " + methodNode.desc);

            String clsSig = classNode.name + "." + methodNode.name + methodNode.desc;

            String excList = null;
            String parList = null;

            String curMap = this.mappings.getProperty(clsSig);
            if (curMap != null)
            {
                List<String> splitMap = StringUtil.splitString(curMap, "|");

                excList = splitMap.get(0);

                // check if we have an old format file with no parameter info
                if (splitMap.size() > 1)
                {
                    parList = splitMap.get(1);
                }
            }

            try
            {
                String exceptions = this.processExceptions(classNode, methodNode, excList);
                String parameters = this.processLVT(classNode, methodNode, parList);

                if ((exceptions.length() > 0) || (parameters.length() > 0))
                {
                    this.outMappings.setProperty(clsSig, exceptions + "|" + parameters);
                }
            }
            catch (RuntimeException e)
            {
                throw new RuntimeException(e.getMessage() + " in " + clsSig);
            }
        }

        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        classNode.accept(writer);

        return writer.toByteArray();
    }

    public String processExceptions(ClassNode classNode, MethodNode methodNode, String excList)
    {
        if (methodNode.exceptions == null)
        {
            methodNode.exceptions = new ArrayList<String>();
        }

        List<String> exceptions = null;

        if (excList != null)
        {
            exceptions = new ArrayList<String>();

            if (!excList.equals(""))
            {
                exceptions = StringUtil.splitString(excList, ",");
            }
        }
        else if (this.initIndex > 0)
        {
            // we aren't autogenerating exceptions yet
        }

        if (exceptions != null)
        {
            MCInjectorImpl.log.log(Level.FINE, "Adding Exceptions: " + excList);
            methodNode.exceptions = exceptions;
        }

        return StringUtil.joinString(methodNode.exceptions, ",");
    }

    public String processLVT(ClassNode classNode, MethodNode methodNode, String parList)
    {

        if (methodNode.localVariables == null)
        {
            methodNode.localVariables = new ArrayList<LocalVariableNode>();
        }

        boolean doLVT = false;
        int idxOffset = 0;
        List<String> argNames = new ArrayList<String>();
        List<Type> argTypes = new ArrayList<Type>();

        if ((methodNode.access & Opcodes.ACC_STATIC) == 0)
        {
            idxOffset = 1;
            argNames.add("this");
            argTypes.add(Type.getType("L" + classNode.name + ";"));
        }

        argTypes.addAll(Arrays.asList(Type.getArgumentTypes(methodNode.desc)));

        if (parList != null)
        {
            doLVT = true;

            if (!parList.equals(""))
            {
                argNames.addAll(StringUtil.splitString(parList, ","));
            }
        }
        else if (this.initIndex > 0)
        {
            // generate a new parameter list based on class and method name
            if (methodNode.name.matches("func_\\d+_.+"))
            {
                String funcId = methodNode.name.substring(5, methodNode.name.indexOf('_', 5));
                for (int x = idxOffset; x < argTypes.size(); x++)
                {
                    argNames.add(String.format("p_%s_%d_", funcId, x));
                }
            }
            // Limit to MineCraft namespace because we don't care about libraries
            else if (methodNode.name.equals("<init>") && classNode.name.startsWith("net/minecraft/"))
            {
                if (argTypes.size() > idxOffset)
                {
                    for (int x = idxOffset; x < argTypes.size(); x++)
                    {
                        argNames.add(String.format("p_i%d_%d_", this.initIndex, x));
                    }
                    this.initIndex++;
                }    
            }
            // functions like equals/valueOf/toString and things outside the net/minecraft namespace
            else
            {
                for (int x = idxOffset; x < argTypes.size(); x++)
                {
                    argNames.add("par" + x);
                }
            }

            doLVT = true;
        }

        if (doLVT)
        {
            if (argNames.size() != argTypes.size())
            {
                throw new RuntimeException("Incorrect argument count");
            }

            // get first instruction, adding label if necessary
            AbstractInsnNode tmp = methodNode.instructions.getFirst();
            if (tmp == null)
            {
                methodNode.instructions.add(new LabelNode());
            }
            else if (tmp.getType() != AbstractInsnNode.LABEL)
            {
                methodNode.instructions.insertBefore(tmp, new LabelNode());
            }
            LabelNode start = (LabelNode)methodNode.instructions.getFirst();

            // get last instruction, adding label if necessary
            tmp = methodNode.instructions.getLast();
            if (tmp == null)
            {
                methodNode.instructions.add(new LabelNode());
            }
            else if (tmp.getType() != AbstractInsnNode.LABEL)
            {
                methodNode.instructions.insert(tmp, new LabelNode());
            }
            LabelNode end = (LabelNode)methodNode.instructions.getLast();

            methodNode.localVariables = new ArrayList<LocalVariableNode>();

            for (int x = 0; x < argNames.size(); x++)
            {
                String arg = argNames.get(x);
                String desc = argTypes.get(x).getDescriptor();

                MCInjectorImpl.log.log(Level.FINE, "Naming argument " + x + " -> " + arg + " " + desc);
                methodNode.localVariables.add(new LocalVariableNode(arg, desc, null, start, end, x));
            }
        }

        List<String> variables = new ArrayList<String>();

        if (methodNode.localVariables != null)
        {
            for (LocalVariableNode lv : methodNode.localVariables)
            {
                if (!lv.name.equals("this"))
                {
                    variables.add(lv.name);
                }
            }
        }

        if (classNode.name.startsWith("net/minecraft/"))
        {
            return StringUtil.joinString(variables, ",");
        }
        else
        {
            return "";
        }
    }
}