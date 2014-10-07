Maven Plugin soap-to-jpa
===========

Maven Plugin that generates JPA with the same structure, as SOAP stubs. It would give you entities ready to be saved in a DB.

Sometimes you need to request a data provided by any API based on JAX-WS specification and put it immediately to a database. A data is represented 
with a stubs that are generated from the WSDL file. The problem is that you don't know the structure of WebService API beforehand and 
you can not prepare your database to keep all the data. In this case you need to create any JPA objects and adjust them
to the generated stubs manually. It requires a lot of work.

```
    ---------------
    |  SOAP Stub  |
    ---------------
           ↓
    *some converter*
           ↓
    ----------------
    |  JPA entity  |
    ----------------
```

After this conversion JPA entities are ready to be saved in a database using EntityManager.

What does the plugin do?
------

After executing, this plugin will perform the following actions:

* scans the *generated-source* directory and collects all the Interfaces (stubs)
* collects all the fields based on getters for each stub
* writes JPA entity with all the fields, constructors and getters/setters
* creates a Factory (*JPAEntitiesFactory*), that can instantiate appropriate JPA object regarding a stub's type

How to set up the plugin?
-----

As long this plugin is not (yet) presented in the Central Maven Repository, you need to install it locally.

1. Clone the current repository and run the command:

 ```
 mvn install package
 ```

2. Add to your POM file the following lines:

 ``` 
            <plugin>
                <groupId>net.pibenchmark</groupId>
                <artifactId>soap-to-jpa-maven-plugin</artifactId>
                <version>1.0-SNAPSHOT</version>
            </plugin>
 ```

3. Run the command *on your project* where the soap-to-jpa plugin was included (after you have generated SOAP stubs):
 
 
 ```
 mvn net.pibenchmark:soap-to-jpa-maven-plugin:1.0-SNAPSHOT:soap-to-jpa
 ```
 
 Or, you can use shorter name. To do that, please add the following section to your *~/.m2/settings.xml* file:
 
 ```
 <pluginGroups>
   <pluginGroup>net.pibenchmark</pluginGroup>
 </pluginGroups>
 ```
 
 and you will be able to use short command:
 
 ```
 mvn soap-to-jpa:soap-to-jpa
 ```
 
4. All the generated JPA can be found in the following directory:
 
 ```
 target/generated-sources/soapToJpa/src
 ```
 
5. To add this folder to your project sources, add these section:
 
 ```
 <build>
        ...
         <resources>
             <resource>
                 <directory>${project.build.directory}/generated-sources/soapToJpa/src</directory>
             </resource>
         </resources>
         ...
</build>
```
 
Configuration
-----
 
There are few parameters that you may use to adjust your work with the plugin.
 
**generatedSoapStubsDir**

The path where plugin should search for the generated SOAP stubs interfaces.

Default value: *"${project.build.directory}/generated-sources/axis2/wsdl2code/src"* (default output directory for [Apache Axis2](http://axis.apache.org/axis2/java/core) framework)

**factoryPackageName**

Besides JPA files, the plugin generates also the factory, allowing to instantiate an appropriate JPA object regarding the stub type. The class name is **JPAEntitiesFactory**.
This parameter will set the package name where this factory should be generated.

Default value: *"org.apache.maven.soap.jpa.factory"*

You can specify these parameters in the following way:

```
            <plugin>
                <groupId>net.pibenchmark</groupId>
                <artifactId>soap-to-jpa-maven-plugin</artifactId>
                <version>1.0-SNAPSHOT</version>
                <configuration>
                    <factoryPackageName>com.taleo.tee400.factory</factoryPackageName>
                    <generatedSoapStubsDir>${project.build.directory}/generated-sources/anotherSoapFramework/src</generatedSoapStubsDir>
                </configuration>
            </plugin>
```