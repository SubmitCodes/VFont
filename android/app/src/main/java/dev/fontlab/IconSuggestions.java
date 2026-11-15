package dev.fontlab;

import java.util.*;

/** Conservative name hints; suggestions always require review. */
final class IconSuggestions {
  private record Candidate(String name,String compact,Set<String> words){}
  private final List<Candidate> candidates=new ArrayList<>();
  private static final Set<String> GENERIC=Set.of("app","apps","icon","icons","android","com","org","net","the","for","pro","free","lite","manager","auto","enable","english","log");
  private static String normalized(String name){return name.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]","");}
  private static Set<String> words(String name){
    Set<String> result=new HashSet<>();
    for(String word:name.replaceAll("([a-z])([A-Z])","$1 $2").toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+"))if(word.length()>=3&&!GENERIC.contains(word))result.add(word);
    return result;
  }
  IconSuggestions(List<String> catalog){
    for(String name:catalog){if(Thread.currentThread().isInterrupted())throw new java.util.concurrent.CancellationException();candidates.add(new Candidate(name,normalized(name),words(name)));}
  }
  static String suggest(String label,String pkg,List<String> catalog){return new IconSuggestions(catalog).suggest(label,pkg);}
  String suggest(String label,String pkg){
    String packageTail=pkg.substring(pkg.lastIndexOf('.')+1),full=normalized(label),best=null;Set<String> labelWords=words(label),packageWords=words(packageTail);
    int bestScore=0;
    for(Candidate icon:candidates){
      if(Thread.currentThread().isInterrupted())throw new java.util.concurrent.CancellationException();
      String name=icon.name(),compact=icon.compact();Set<String> candidate=icon.words();int score=0;
      if(full.length()>=3&&full.equals(compact))score=1000;
      else if(compact.length()>=4&&packageWords.contains(compact))score=900;
      else{
        for(String word:candidate)if(labelWords.contains(word))score+=100+Math.min(20,word.length());
        if(score==0)for(String word:candidate)if(packageWords.contains(word))score+=50+Math.min(20,word.length());
        if(score==0)for(String word:candidate)if(word.length()>=5&&word.length()*2+2>=packageTail.length()&&packageTail.contains(word))score+=20+Math.min(20,word.length());
        if(score>0)score=Math.max(1,score-candidate.size());
      }
      if(score>bestScore||score>0&&score==bestScore&&(name.length()<best.length()||name.length()==best.length()&&name.compareTo(best)<0)){bestScore=score;best=name;}
    }
    return best;
  }
}
