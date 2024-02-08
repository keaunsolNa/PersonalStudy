# Implement

```java
package test;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;

public class test {
	  
	public static void main(String[] args) {
		
		String filePath = "C:\\test\\test.txt";
		File file = new File(filePath);
		
		if(!file.exists())
			
			try {
				
				createFile(filePath);
				System.out.println(readFile(file, filePath));;
				
				BufferedReader br = new BufferedReader(new InputStreamReader(System.in));
				StringBuilder sb = new StringBuilder();
				String input = "";
				
				while(!(input = br.readLine()).equals("STOP")) sb.append(input + "\n");
				updateFile(filePath, sb.toString());
				
				System.out.println(readFile(file, filePath));;
				
			} catch (IOException e) { e.printStackTrace(); }
	}
	
	private static String readFile(File file, String filePath) throws IOException {
		
		StringBuilder returnText = new StringBuilder();
		int nBuffer;
		BufferedReader buffFileReader = new BufferedReader(new FileReader(file));
		
		while((nBuffer = buffFileReader.read()) != -1) returnText.append((char)nBuffer);
		
		buffFileReader.close();
		
		return returnText.toString();
		
	}
	
	private static void updateFile(String filePath, String text) throws IOException {
		
		File file = new File(filePath);
		String fileText = readFile(file, filePath);
		BufferedWriter bufw = new BufferedWriter(new FileWriter(file));
		
		text = fileText + text;
		
		bufw.write(text, 0, text.length());
		bufw.flush();
		bufw.close();
		
	}

	private static void createFile(String filePath) throws IOException {
		
		System.out.println("CREATEFILE");
		int last = filePath.lastIndexOf("\\");
		String dir = filePath.substring(0, last);
		String name = filePath.substring(last + 1, filePath.length());
		
		File dirFolder = new File(dir);
		dirFolder.mkdir();
		
		File f = new File(dirFolder, name);
		f.createNewFile();
		
	}

}
```