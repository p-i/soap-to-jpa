package net.pibenchmark.testFiles;

import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlElements;
import java.util.List;

/**
 * Created by ilja on 03/03/15.
 */
public class SimpleClassWithPolymorphicField {

    @XmlElements({
            @XmlElement(name = "Object"), // <-- should be ignored, because it is generic type
            @XmlElement(name = "SimpleClassOne", type = SimpleClassOne.class),
            @XmlElement(name = "SimpleClassTwo", type = SimpleClassTwo.class),
    })
    protected List<Object> lstTest;

}
